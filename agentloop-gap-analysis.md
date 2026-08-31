# 龙虾 AgentLoop vs OpenCode AgentLoop 细节差距分析

> 生成时间：2026-08-29
> 对比对象：龙虾 `AgentLoop.java`（M4）vs OpenCode `prompt.ts` / `processor.ts` / `retry.ts` / `compaction.ts` / `overflow.ts`
> 参考实现路径：`D:\AIAgent\aicode\opencode-dev\packages\opencode\src\session\`
> 结论：龙虾 AgentLoop 具备基础循环骨架（状态机、流式、工具执行、压缩、Plan 模式、子代理、中断），但相比 opencode 缺少 **23 项**生产级鲁棒性能力，其中 **7 项为高优先级**。

---

## 一、总体对比

| 维度 | 龙虾（当前） | OpenCode |
|------|-------------|----------|
| 循环结构 | `while(true)` + MAX_STEPS=50 硬终止 | `while(true)` + 可配置 maxSteps + 优雅降级（注入 prefill） |
| LLM 事件模型 | 4 种（TextDelta / ToolCall / Finish / Error） | 12+ 种（text / reasoning / tool-input / tool-call / tool-result / step / finish / provider-error 等） |
| LLM 重试 | ❌ 无 | ✅ 指数退避 + 抖动，最多 5 次，尊重 retry-after 头 |
| 错误分类 | ❌ 单一 Error(cause) | ✅ 6 类（Aborted / ContextOverflow / API / Auth / OutputLength / Unknown） |
| Reasoning 流 | ❌ 不支持 | ✅ reasoning-start/delta/end，支持 Claude/o1/o3 |
| 文件快照 & Revert | ❌ 无 | ✅ 每步快照 + patch diff + revert 撤销 |
| Abort 信号传播 | ⚠️ 仅循环顶部检查 Set | ✅ AbortController 传播到 HTTP + 工具 + 子任务 |
| 上下文压缩 | ⚠️ 固定保留尾部 6 条 | ✅ token 预算倒序选 tail + pruning + 溢出错误 |
| Runner 状态机 | ⚠️ busySessions Set | ✅ Idle/Running/Shell/ShellThenRun 四态 |
| 工具调用修复 | ❌ 无 | ✅ 小写兜底 + invalid 工具路由 |
| 权限回复 | ⚠️ allowed/denied | ✅ once/always/reject/correct |
| 子代理权限派生 | ❌ 无 | ✅ 合并父 deny + external_directory |
| 标题生成 | ❌ 无 | ✅ step 1 异步 fork |
| Structured Output | ❌ 无 | ✅ JSON schema + 校验 |
| Token/成本追踪 | ⚠️ 仅记录数量 | ✅ 全量成本计算（tier 定价、cache、Copilot） |
| 图片/附件 | ❌ 无 | ✅ 归一化/缩放/provider 适配 |
| LSP 诊断 | ❌ 无 | ✅ 编辑后诊断 |
| 自动格式化 | ❌ 无 | ✅ 编辑后格式化 |
| OpenTelemetry | ❌ 无 | ✅ 可选链路追踪 |
| Provider 优化 | ⚠️ 基础 SSE | ✅ 双运行时 + OpenAI/Azure/Copilot 特化 |

---

## 二、高优先级差距（P0）

### GAP-01：LLM 重试机制

**OpenCode 实现**（`retry.ts:183-207`）：

```
重试策略参数：
  RETRY_INITIAL_DELAY = 2000ms
  RETRY_BACKOFF_FACTOR = 2
  RETRY_JITTER_FACTOR  = 0.25（25% 随机抖动）
  RETRY_MAX_DELAY_NO_HEADERS = 30_000ms
  RETRY_MAX_RETRIES    = 5

可重试模式（正则匹配）：
  - HTTP 429 / 5xx
  - "rate limit" / "overloaded" / "try again later"
  - 网络超时 / 连接重置
  - 上下文溢出错误 → 永不重试

HTTP 头尊重：
  - retry-after-ms（毫秒精度）
  - retry-after（秒精度，HTTP 1.1 标准）

应用方式：Effect.retry(SessionRetry.policy(...))
```

重试期间通过 `processor.ts:660-674` 发布 `status: { type: "retry", attempt, message, action, next }` 事件，前端可展示"正在重试（第 N 次），X 秒后重试"。

**龙虾现状**（`AgentLoop.java:355-360`）：

```java
case LlmEvent.Error e -> {
    errored = true;
    store.addPart(assistant.id(), new Part.Text(
            "LLM 错误: " + e.cause().getMessage(), false, false));
}
// ...
if (errored) return;  // 直接终止回合，无重试
```

`OpenAiCompatProvider.java:93-95` 捕获所有异常直接返回 `Stream.of(new LlmEvent.Error(e))`，不区分可重试 vs 不可重试。

**差距说明**：生产环境中 LLM API 经常返回 429（限流）或 503（过载），无重试意味着一次限流就终止整个回合，用户体验极差。

**建议方案**：
1. `LlmEvent` 新增 `Error` 子类型分类：`Error.Retriable(cause)` / `Error.Fatal(cause)`
2. `OpenAiCompatProvider` 解析 HTTP 状态码，429/5xx → Retriable，4xx（非 429）→ Fatal
3. `AgentLoop.runLoop` 在 `LlmEvent.Error` 分支判断：Retriable → 指数退避重试（max 5），Fatal → 终止
4. 重试期间发布 `Events.RETRY_STARTED` 事件（含 attempt/delay）

---

### GAP-02：错误分类体系

**OpenCode 实现**（`message-v2.ts:606-734`）：

| 错误类型 | 触发条件 | 处理路径 |
|---------|---------|---------|
| `AbortedError` | DOMException name="AbortError" | 中断清理，不重试 |
| `ContextOverflowError` | API 返回 context_length_exceeded | 触发自动压缩，不重试 |
| `APIError` | HTTP 非 2xx | 按 statusCode 判断可重试性 |
| `AuthError` | LoadAPIKeyError | 不重试，提示认证失败 |
| `OutputLengthError` | max_tokens 截断 | 直通 |
| `NamedError.Unknown` | 兜底 | 未知错误 |

**龙虾现状**（`LlmEvent.java:12`）：

```java
record Error(Throwable cause) implements LlmEvent {}
```

单一 Error 类型，`cause` 可能是 `IOException`（网络）、`IllegalStateException`（HTTP 4xx）、`JsonProcessingException`（解析失败）等，但 AgentLoop 不区分，统一处理为"LLM 错误: {message}"并终止。

**差距说明**：无法区分"限流可重试"与"认证失败不可重试"，也无法区分"上下文溢出应压缩"与"服务端 500 应重试"。

**建议方案**：
1. `LlmEvent.Error` 改为 sealed interface，子类型：`Network`、`RateLimited`、`AuthFailed`、`ContextOverflow`、`ContentFilter`、`Unknown`
2. `OpenAiCompatProvider` 根据 HTTP 状态码和响应体分类
3. `AgentLoop` 按类型走不同路径：RateLimited → 重试；ContextOverflow → 压缩；AuthFailed → 终止+提示

---

### GAP-03：Reasoning / Thinking 流支持

**OpenCode 实现**（`ai-sdk.ts` + `processor.ts:467-475`）：

```
LLMEvent 事件流包含：
  reasoning-start  → 创建 Part.Reasoning（pending）
  reasoning-delta  → 追加推理文本（流式推送前端）
  reasoning-end    → 完成 Part.Reasoning

支持模型：
  - Claude 3.5+（extended thinking）
  - OpenAI o1 / o3 / o4-mini（reasoning tokens）
  - DeepSeek R1（reasoning_content 字段）

消息转换（message-v2.ts:288-310）：
  - Anthropic：保留 signed reasoning blocks（adaptive thinking）
  - OpenAI：reasoning tokens 计入 usage.reasoning_tokens
```

**龙虾现状**（`LlmEvent.java:4-12`）：

```java
public sealed interface LlmEvent permits
    LlmEvent.TextDelta, LlmEvent.ToolCall, LlmEvent.Finish, LlmEvent.Error {
    // 无 Reasoning 事件类型
}
```

`Part` 模型中有 `Part.Reasoning` 类型（`TokenEstimator.java:27` 可识别），但 `LlmEvent` 无对应事件，`OpenAiCompatProvider` 不解析 `reasoning_content` 字段，AgentLoop 不处理 reasoning 流。

**差距说明**：不支持推理模型（o1/o3/Claude thinking/DeepSeek R1），这些模型在编码场景中推理质量显著优于普通模型。

**建议方案**：
1. `LlmEvent` 新增 `ReasoningDelta(String text)` 事件
2. `OpenAiCompatProvider` 解析 `delta.reasoning_content`（DeepSeek）和 `delta.reasoning`（OpenAI o 系列）
3. `AgentLoop` 在 reasoning 事件分支中累积文本，发布 `Events.REASONING_DELTA` 事件
4. `LlmEvent.Usage` 新增 `reasoningTokens` 字段

---

### GAP-04：文件快照 & Revert 系统

**OpenCode 实现**：

| 组件 | 文件 | 功能 |
|------|------|------|
| Snapshot 服务 | `session/snapshot.ts` | 每步前 `track()` 捕获文件系统快照 |
| Patch 计算 | `processor.ts:477-483` | 步后 `patch()` 计算 diff，存为 `patch` part |
| Revert | `session/revert.ts` | `revert(patches)` 撤销所有 patch |
| Diff 展示 | `snapshot.diffFull(from, to)` | 全文件 diff |
| 会话摘要 | `session/summary.ts` | `computeDiff()` 提取每轮文件变更 |
| EditTool | `tool/edit.ts` | 每文件信号量锁、行尾检测、BOM 处理、LSP 诊断、格式化 |
| ApplyPatch | `tool/apply_patch.ts` | patch 格式编辑（替代方案） |

**龙虾现状**（`EditTool.java:36-58`）：

```java
public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
    Path path = Path.of(args.get("file_path").asText());
    String oldStr = args.get("old_string").asText();
    String newStr = args.get("new_string").asText();
    boolean all = args.path("replace_all").asBoolean(false);
    // ... 纯字符串 indexOf + substring 替换
    Files.writeString(path, updated, StandardCharsets.UTF_8);
    return ToolResult.of("Edit " + path.getFileName(), "Replaced ...");
}
```

- 无快照追踪，无法 revert
- 无 diff 展示（前端无法显示代码变更）
- 无每文件锁（并发编辑不安全）
- 无行尾检测（可能改变 CRLF/LF）
- 无 BOM 处理（可能引入/丢失 BOM）
- 无 LSP 诊断
- 无自动格式化

**差距说明**：无 revert 意味着 agent 错误修改文件后无法撤销；无 diff 展示意味着用户无法审查 agent 的代码变更。

**建议方案**：
1. 新增 `SnapshotService`：每步前记录文件 mtree 哈希
2. `EditTool` / `WriteTool` 执行后计算 diff，存为 `Part.Patch`
3. 新增 `revert` WS 命令：按 patch 列表逆向恢复
4. `EditTool` 增加行尾检测（`detectLineEnding`）和 BOM 保留
5. `EditTool` 增加每文件 `ReentrantLock`（同 session 内工具顺序执行，但 background_spawn 可能并发）

---

### GAP-05：Abort 信号传播

**OpenCode 实现**：

```
信号传播链：
  session.cancel(sessionID)
    → RunState.cancel(sessionID)
      → 取消所有 background jobs（递归，跟随 parent/child 关系）
      → Runner.cancel → 中断 fiber
        → AbortController.abort()
          → LLM HTTP 请求中断（HttpRequest.Builder.timeout + abortSignal）
          → 工具执行中断（ToolContext.abortSignal）
            → TaskTool 监听 abort 取消子会话
            → BashTool 监听 abort 杀进程

中断清理（processor.ts:539-597 cleanup()）：
  1. 完成 pending snapshot patch
  2. 完成当前 text part
  3. 完成所有 pending reasoning parts
  4. 等待 in-flight 工具调用（250ms 超时/个）
  5. 标记仍在运行的工具为 errored + interrupted: true
  6. 设置 assistantMessage.time.completed
```

**龙虾现状**（`AgentLoop.java:83-90, 285-290`）：

```java
private final java.util.Set<String> abortRequests =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

public void requestAbort(String sessionId) {
    abortRequests.add(sessionId);
}

private boolean consumeAbort(String sessionId) {
    return abortRequests.remove(sessionId);
}

// 在 runLoop 循环顶部检查：
if (consumeAbort(sessionId)) {
    var asst = store.appendAssistant(sessionId);
    store.addPart(asst.id(), new Part.Text(
            "用户已中断（interrupt），回合终止。", true, false));
    return;
}
```

- 仅在循环迭代顶部检查，无法中断正在执行的 LLM HTTP 请求
- 无法中断正在运行的工具（如长时间 bash 命令）
- 无 background_spawn 子任务的递归取消
- 无中断清理（pending parts 丢弃、in-flight 工具不等待）

**差距说明**：用户按"停止"后，如果 LLM 正在流式返回或 bash 正在执行 60 秒命令，abort 不会立即生效，需等到下一个循环迭代。

**建议方案**：
1. `LlmRequest` 新增 `java.util.concurrent.atomic.AtomicBoolean abortFlag` 字段
2. `OpenAiCompatProvider.streamBlocking` 在读取 SSE 行时检查 abortFlag，提前关闭 `resp.body()` 并返回
3. `ToolContext` 新增 `abortFlag`，`BashTool` 启动子进程后轮询，abort 时 `process.destroyForcibly()`
4. `BackgroundSpawnTool` 子任务支持递归 abort（通过 `abortRequests` Set 传播到子 sessionId）
5. 中断清理：完成当前 text part、标记 in-flight 工具为 interrupted

---

### GAP-06：精细上下文压缩

**OpenCode 实现**（`compaction.ts` + `overflow.ts`）：

```
1. 溢出检测（overflow.ts）：
   usable = model.limit.input - reserved
   reserved = min(20000, maxOutputTokens)
   isOverflow = totalTokens(input+output+cache) > usable

2. 压缩选择（compaction.ts select()）：
   - 从尾部倒序遍历轮次
   - 按 preserveRecentBudget（token 预算）决定保留多少轮
   - 可拆分单轮（保留该轮的部分消息）
   - 惰性估算（仅估算 retained tail，不估算全量）

3. 压缩执行：
   - head 序列为对话 transcript
   - 调用 compaction agent（无工具，纯文本）
   - 如压缩自身溢出 → 返回 "stop" + ContextOverflowError
   - 成功后注入 "continue" 合成消息

4. Pruning（compaction.ts:273-317）：
   - 循环结束后异步执行
   - 倒序跳过最后 2 轮
   - 旧工具输出标记为 compacted（清除文本）
   - 保护带：40K tokens（PRUNE_PROTECT）
   - 最低阈值：20K tokens
   - 受保护工具（skill）永不清除

5. 消息重排（message-v2.ts:521-572）：
   [compaction-user, summary, ...retained tail, ...continue-user]
   确保模型先看到摘要，再看最近上下文，最后看当前消息
```

**龙虾现状**（`AgentLoop.java:247-279`）：

```java
private static final double COMPACTION_TRIGGER = 0.7;
private static final int COMPACTION_KEEP_TAIL = 6;

private List<Message> autoCompact(String sessionId, List<Message> history) {
    if (history.size() <= COMPACTION_KEEP_TAIL) return history; // 太短不压
    // 固定保留尾部 6 条，其余全部摘要
    List<Message> head = history.subList(0, history.size() - COMPACTION_KEEP_TAIL);
    // ... 规则摘要或 LLM summarizer
    store.compact(sessionId, keepFromId, summary);
    return store.loadActive(sessionId);
}
```

- 固定保留 6 条（不按 token 预算动态计算）
- 无 pruning（旧工具输出不清除，持续占用上下文）
- 无溢出错误（压缩后仍可能溢出，无二次检测）
- 无消息重排（压缩摘要位置可能不正确）
- 无惰性估算（每次估算全量历史）
- 无受保护工具概念

**差距说明**：长会话（50+ 步）中，旧工具输出（如大段 grep 结果）持续占用上下文，固定保留 6 条可能过多或过少，压缩后仍可能溢出但无错误反馈。

**建议方案**：
1. `autoCompact` 改为 token 预算倒序选择 tail（而非固定条数）
2. 新增 `prune()` 方法：循环结束后异步清除旧工具输出（保留 40K 保护带）
3. 压缩后二次检测溢出 → 发布 `Events.CONTEXT_OVERFLOW` 错误事件
4. `Part.Tool` 新增 `compacted` 标记，`toChatMessages` 跳过已清除的输出

---

### GAP-07：Runner 状态机

**OpenCode 实现**（`runner.ts`）：

```
四态状态机（SynchronizedRef 原子转换）：

  ┌──────────┐  ensureRunning  ┌─────────┐
  │  Idle    │ ──────────────→ │ Running │
  └──────────┘                 └─────────┘
       ↑                            │
       │ cancel                     │ shell.start
       │                            ↓
  ┌──────────┐  cancel       ┌──────────────┐
  │  Idle    │ ←──────────── │    Shell     │
  └──────────┘               └──────────────┘
       ↑                            │
       │                            │ ensureRunning
       │                            ↓
  ┌──────────┐  cancel       ┌──────────────────┐
  │  Idle    │ ←──────────── │ ShellThenRun     │
  └──────────┘               └──────────────────┘

语义：
  - Idle: 无工作运行
  - Running: agent loop 执行中
  - Shell: 用户发起 shell 命令执行中
  - ShellThenRun: shell 即将完成，有 run 排队等待

关键能力：
  - shell 命令穿插（用户在 agent 运行时执行 /bash）
  - 排队等待（shell 完成后自动启动排队的 run）
  - 原子状态转换（无竞态）
```

**龙虾现状**（`AgentLoop.java:72-75, 131-135`）：

```java
private final java.util.Set<String> busySessions =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

public void run(String sessionId) {
    if (!busySessions.add(sessionId)) {
        return; // 已在运行，输入由 WsHandler 入队
    }
    // ... runLoop
}
```

- 仅二态：busy / not busy
- 无 shell 穿插（用户执行 bash 命令需等 agent 完成）
- 无排队等待（shell 完成后不会自动启动排队的 run）
- 无原子状态转换（busySessions.add 是原子的，但无状态机语义）

**差距说明**：用户在 agent 运行时无法执行临时 shell 命令（如 `git status`），必须等 agent 完成。

**建议方案**：
1. 新增 `RunnerState` 枚举：`IDLE / RUNNING / SHELL / SHELL_THEN_RUN`
2. `AgentLoop.run` 改为通过 `RunnerState.ensureRunning` 调度
3. WS `shell.run` 命令通过 `RunnerState.startShell` 执行
4. Shell 完成后如有排队 run，自动启动

---

## 三、中优先级差距（P1）

### GAP-08：Max Steps 优雅降级

**OpenCode**（`prompt.ts:1178-1181` + `max-steps.ts`）：

```typescript
const maxSteps = agent.steps ?? Infinity
const isLastStep = step >= maxSteps
// 注入 assistant prefill（非终止）：
messages: [...modelMsgs, ...(isLastStep ? [{
  role: "assistant",
  content: MAX_STEPS_PROMPT  // "CRITICAL - MAXIMUM STEPS REACHED..."
}] : [])]
```

模型收到 prefill 后被强制停止工具调用，产出文本总结。用户可继续输入以恢复工具能力。

**龙虾**（`AgentLoop.java:223, 292-296`）：

```java
private static final int MAX_STEPS = 50;
if (++step > MAX_STEPS) {
    var asst = store.appendAssistant(sessionId);
    store.addPart(asst.id(), new Part.Text(
            "达到最大步数限制（" + MAX_STEPS + "），回合终止。", true, false));
    return;  // 硬终止
}
```

**差距**：硬终止 vs 优雅降级。opencode 让模型自己总结已完成的工作，lobster 直接截断。

**建议方案**：`MAX_STEPS` 达到时，在 `toChatMessages` 末尾注入一条 assistant prefill 消息（"已达最大步数，请用文本总结"），让模型自然收尾。

---

### GAP-09：工具调用修复

**OpenCode**（`llm.ts:296-312`）：

```typescript
experimental_repairToolCall: async ({ toolCall, error, messages }) => {
  // 1. 尝试小写工具名
  const lower = toolCall.name.toLowerCase()
  if (tools[lower]) return { ...toolCall, name: lower }
  // 2. 路由到 "invalid" 工具报错
  return { ...toolCall, name: "invalid", input: { error: `Unknown tool: ${toolCall.name}` } }
}
```

**龙虾**（`AgentLoop.java:419-424`）：

```java
Tool tool = tools.get(call.name());
if (tool == null || !toolAllowed(call.name())) {
    store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
            new Part.ToolState.Error("未知工具或角色无权限: " + call.name())));
    // ... 直接报错，不修复
}
```

**差距**：LLM 偶尔返回 `Read` 而非 `read`，opencode 自动修复，lobster 直接报错浪费一轮。

**建议方案**：`tools.get(call.name())` 失败时，尝试 `tools.get(call.name().toLowerCase())`。

---

### GAP-10：权限回复选项

**OpenCode**（`permission/index.ts:109-167`）：

| 回复 | 语义 |
|------|------|
| `once` | 仅本次允许 |
| `always` | 允许 + 添加 approved 规则 + 自动批准后续匹配的 pending 请求 |
| `reject` | 拒绝（可带 feedback 纠正参数） |
| `correct` | 拒绝 + 返回修正后的参数供模型重试 |

**龙虾**（`PermissionEngine` + `PermissionReply`）：

```java
// PermissionReply 仅 allowed/denied + feedback
// 无 once/always 区分
// 无 correct（参数纠正）
// 无自动批准后续匹配请求
```

**差距**：用户每次都要确认相同操作（如多次 `read` 不同文件），无"始终允许"选项。

**建议方案**：`PermissionReply` 新增 `scope` 字段（`ONCE / ALWAYS`），`always` 时写入 approved 规则到 `PermissionEngine`。

---

### GAP-11：子代理权限派生

**OpenCode**（`subagent-permissions.ts`）：

```typescript
// 子代理权限 = 父会话 deny 规则 + external_directory 规则
// + 默认 deny todowrite 和 task（如果子代理未显式允许）
function derivePermissions(parentSession): PermissionRuleset {
  const denies = parentSession.permission.rules.filter(r => r.action === "deny")
  const external = parentSession.permission.rules.filter(r => isExternalDir(r))
  return { rules: [...denies, ...external, ...defaultDenies] }
}
```

**龙虾**：子代理（`TaskTool` / `BackgroundSpawnTool`）创建子会话时不派生权限，使用默认权限规则。

**差距**：父会话禁止访问的目录，子代理可能访问（权限逃逸）。

**建议方案**：`TaskTool` / `BackgroundSpawnTool` 创建子会话时，合并父会话的 deny 规则到子会话权限。

---

### GAP-12：标题生成

**OpenCode**（`prompt.ts:1183-1186`）：

```typescript
step++
if (step === 1)
  yield* title({ session, modelID, providerID, history: msgs })
    .pipe(Effect.ignore, Effect.forkIn(scope))  // 异步 fork
```

使用专用 "title" agent（`agent.ts:127-155`），无工具，短 prompt，生成会话标题。

**龙虾**：无标题生成。会话列表显示为 sessionId 或空标题。

**建议方案**：`AgentLoop.run` 在 step 1 时 fork 虚拟线程，用 LLM 生成标题（单轮、无工具），写入 `session.title`。

---

### GAP-13：Structured Output（JSON Schema）

**OpenCode**（`prompt.ts:1290-1308`）：

```typescript
const format = agent.output  // 可配置 JSON schema
if (format.type === "json_schema") {
  // 请求时设置 response_format: { type: "json_schema", schema: format.schema }
  // 完成后校验输出是否符合 schema
  // 不符合 → 报错
}
```

**龙虾**：`LlmRequest` 无 `responseFormat` 字段，`OpenAiCompatProvider` 不发送 `response_format`。

**差距**：无法强制 LLM 返回结构化 JSON（如任务解析、分类等场景）。

**建议方案**：`LlmRequest` 新增 `JsonNode responseFormat` 字段，`OpenAiCompatProvider.buildRequest` 注入 `response_format`。

---

### GAP-14：Content Filter 处理

**OpenCode**（`prompt.ts:1301-1308`）：

```typescript
if (handle.message.finish === "content-filter") {
  // 作为错误抛出，告知用户内容被过滤
}
```

**龙虾**：`LlmEvent.Finish.reason` 可能是 `"content_filter"`，但 AgentLoop 不特殊处理，视为正常完成。

**建议方案**：`Finish` 分支检查 `reason == "content_filter"` → 发布 `Events.CONTENT_FILTERED` 错误事件。

---

### GAP-15：Token / 成本追踪

**OpenCode**（`session.ts:338-405`）：

```
getUsage() 计算：
  - input/output/reasoning/cache-read/cache-write tokens
  - context tier 定价（>200K tokens 更贵）
  - Copilot nano-AIU 计费
  - AI SDK v6 归一化（从 input 减去 cache tokens）
  - 按 model pricing 表计算 $ 成本
```

**龙虾**（`AgentLoop.java:384`）：

```java
store.addPart(assistant.id(), new Part.StepFinish(
        toolCalls.isEmpty() ? finishReason : "tool_calls",
        usage.inputTokens(), usage.outputTokens(), 0));  // 第三个参数 0 = cost
```

仅记录 input/output token 数，cost 恒为 0。

**建议方案**：新增 `PricingTable`（model → price per 1K tokens），`StepFinish` 计算实际成本，`UsageStore` 汇总。

---

### GAP-16：后台任务提升（Foreground → Background Promotion）

**OpenCode**（`task.ts` + `background/job.ts`）：

```typescript
// 前台 task 执行中可被提升为 background
background.extend({
  onPromote: () => {
    // 更新工具元数据为 background
    // 启动通知 watcher
  }
})
// 用户可继续与父会话交互，task 完成后通知
```

**龙虾**（`BackgroundSpawnTool.java`）：仅支持 fire-and-forget（创建即为后台），前台 `TaskTool` 无法中途转为后台。

**差距**：长时间前台 task 阻塞父会话，用户无法中途交互。

**建议方案**：`TaskTool` 执行中检查 `promoteToBackground` 标志，若设置则转为虚拟线程后台执行 + announce。

---

## 四、低优先级差距（P2）

### GAP-17：图片 / 附件处理

**OpenCode**：
- `image.ts`：图片归一化/缩放（限制尺寸）
- `message-v2.ts:131-415`：工具结果中的 media 处理
  - 不支持 media 的 provider：剥离 media
  - OpenAI 兼容 API：media 作为独立 user 消息注入
  - 图片 resize 前存储

**龙虾**：`Part` 无 `Image` 子类型，`ChatMsg` 无 media 字段，不支持图片输入/输出。

---

### GAP-18：LSP 诊断集成

**OpenCode**（`edit.ts`）：编辑后调用 LSP 获取诊断信息（错误、警告），附在工具结果中返回给模型。

**龙虾**：无 LSP 集成，编辑后不诊断。

---

### GAP-19：自动格式化

**OpenCode**（`edit.ts`）：编辑后调用 formatter（prettier / gofmt / rustfmt 等）自动格式化修改的文件。

**龙虾**：无格式化集成。

---

### GAP-20：工具输出截断到临时文件

**OpenCode**（`truncate.ts` + `tools.ts:196`）：

```typescript
// 超长输出写入临时文件，返回路径
if (output.length > limit) {
  const path = writeTempFile(output)
  return `Output too large. Written to ${path}. First 500 chars:\n${output.slice(0, 500)}`
}
```

**龙虾**（`AgentLoop.java:531-533`）：

```java
private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "\n...truncated...";
}
```

固定截断 4000/8000 字符，超长内容直接丢弃，模型无法通过 `read` 工具查看完整输出。

---

### GAP-21：OpenTelemetry 追踪

**OpenCode**（`llm.ts:208-222`）：可选 OpenTelemetry 链路追踪，注入 sessionID 属性。

**龙虾**：无追踪。

---

### GAP-22：Provider 特定优化

**OpenCode**（`request.ts`）：

| Provider | 特化处理 |
|---------|---------|
| OpenAI OAuth | system prompt 作为 `instructions` 选项（非 system 消息） |
| Azure | 移除 `reasoningSummary` / `include` 选项 |
| OpenAI/Azure/Bedrock | 工具 schema `strict: false` |
| Copilot | 重放 tool_calls 时添加 `_noop` 工具 |
| 通用 | session affinity headers（provider 侧路由亲和） |
| 双运行时 | AI SDK + native runtime（绕过 AI SDK 开销） |

**龙虾**（`OpenAiCompatProvider.java`）：统一 SSE 处理，无 provider 特化。

---

### GAP-23：指令文件去重

**OpenCode**（`instruction.ts:179-221`）：读取 AGENTS.md / CLAUDE.md / CONTEXT.md 时，按消息去重附近文件（`claims` set），避免同一指令文件在一条消息中出现多次。

**龙虾**（`PromptAssembler.java`）：向上查找 AGENTS.md（max 16KB），但无去重逻辑。

---

## 五、优先级排序与实施建议

### P0（生产必备，建议 M5 首批）

| # | 差距 | 工作量估算 | 依赖关系 |
|---|------|-----------|---------|
| 01 | LLM 重试机制 | 2d | 依赖 GAP-02 错误分类 |
| 02 | 错误分类体系 | 2d | 无 |
| 03 | Reasoning 流支持 | 1.5d | 无 |
| 04 | 文件快照 & Revert | 3d | 无 |
| 05 | Abort 信号传播 | 2d | 无 |
| 06 | 精细上下文压缩 | 3d | 无 |
| 07 | Runner 状态机 | 2d | 无 |

**P0 合计：约 15.5 人日**

### P1（体验提升，建议 M5 二批）

| # | 差距 | 工作量估算 |
|---|------|-----------|
| 08 | Max Steps 优雅降级 | 0.5d |
| 09 | 工具调用修复 | 0.5d |
| 10 | 权限回复选项 | 1.5d |
| 11 | 子代理权限派生 | 1d |
| 12 | 标题生成 | 1d |
| 13 | Structured Output | 1d |
| 14 | Content Filter 处理 | 0.5d |
| 15 | Token/成本追踪 | 2d |
| 16 | 后台任务提升 | 2d |

**P1 合计：约 10 人日**

### P2（锦上添花，按需排期）

| # | 差距 | 工作量估算 |
|---|------|-----------|
| 17 | 图片/附件处理 | 3d |
| 18 | LSP 诊断集成 | 2d |
| 19 | 自动格式化 | 1.5d |
| 20 | 工具输出截断到临时文件 | 0.5d |
| 21 | OpenTelemetry 追踪 | 1d |
| 22 | Provider 特定优化 | 3d |
| 23 | 指令文件去重 | 0.5d |

**P2 合计：约 11.5 人日**

### 总计：约 37 人日

---

## 六、龙虾已有但 opencode 无的能力（反向差距）

| 能力 | 龙虾实现 | 说明 |
|------|---------|------|
| 队列模式（4 种） | `QueueMode.java` | STEER/FOLLOWUP/COLLECT/INTERRUPT 四种输入调度策略，opencode 无此概念 |
| Writer Claim 围栏 | `WriterClaimStore` | 会话级写入权代际围栏，防止并发 run 交叉写入 |
| HookEngine | `HookEngine.java` | tool.before（block 语义）/ tool.after + skill/mcp 完成钩子 |
| 五层记忆 | `MemoryStore` | episodic 写入 + memory_search 工具 + Dreaming sweep + provenance 闸门 |
| 审计日志 | `AuditStore` | run.start/end、工具执行审计记录 |
| 角色工具过滤 | `toolFilter` | RBAC 8 角色 × 工具过滤谓词 |
| Plan 模式 reminder 注入 | `PlanMode.reminder()` | 在最后一条 user 消息尾部注入 system-reminder |

这些是龙虾企业级多角色定位的差异化能力，opencode 作为个人编码工具不需要。
