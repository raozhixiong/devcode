# OpenCode 功能清单与核心设计全解

> 来源：`D:\workspace\sourcecode\opencode-dev` 深度研究报告
> 用途：作为 AI 工作台（开发者角色界面）的参考蓝本

---

## 一、产品功能全景

### 1. 用户可见功能

| 功能 | 说明 |
|---|---|
| 对话式编程 | 基于终端的 AI 对话，支持流式输出、markdown 渲染、diff 渲染 |
| 多 Agent 模式 | Tab 切换 build / plan 等主 agent；`@agent` 直接指派子 agent |
| 工具调用 | 读/写/编辑/glob/grep/shell/lsp/task/todo/webfetch/websearch 等内置工具 |
| 权限确认 | 危险操作弹 ask 对话框（Yes/Always/Reject 三键），diff 预览 |
| 会话管理 | 多会话、fork、undo/redo（revert）、自动标题、自动摘要、分享 URL |
| 上下文压缩 | token 溢出自动 compact，保留近 N token |
| Todo 管理 | TodoWrite 工具维护任务清单，TUI 侧边栏展示 |
| 子代理 | Task 工具委派 explore/general 子 agent，支持后台运行、task_id 续传 |
| 斜杠命令 | 命令=键位=slash 命令三位一体（同源注册） |
| 主题系统 | 30+ 内置主题 JSON，60 个语义 token，支持自定义 |
| 插件体系 | JS/TS 插件注入工具、hook（chat.system.transform、tool.execute.before/after 等） |
| MCP 支持 | MCP server 工具动态注入 + resource 三件套工具 |
| 多模型接入 | 26 个 provider（anthropic/openai/google/bedrock/...），models.dev 元数据 |
| 分享 | 会话上传生成分享 URL |
| Plan 模式 | 只读规划 + 计划文件写入 + plan_exit 无缝切换到 build |

### 2. 架构分层

```
┌─────────────────────────────────────────────┐
│ TUI (packages/tui, SolidJS + @opentui)      │  纯前端
├─────────────────────────────────────────────┤
│ SDK Client / SSE 事件流                      │  通信层
├─────────────────────────────────────────────┤
│ HTTP Server (packages/server + protocol)    │  API 层
├─────────────────────────────────────────────┤
│ 核心服务层（Effect-TS Service/Layer）        │
│  Session / Agent / Tool / Permission /      │
│  Provider / LLM / Instruction / Snapshot    │
├─────────────────────────────────────────────┤
│ 存储层：SQLite (drizzle) + 事件表 + 文件系统 │
└─────────────────────────────────────────────┘
```

**双进程模型**：`opencode` CLI 启动拉起 HTTP server，TUI 作为纯前端通过 SDK + SSE 驱动，数据同步在 `context/sync.tsx`。

---

## 二、核心设计（机制级）

### 1. 提示词组装机制（分层拼装）

调用链：`session/prompt.ts runLoop()` → `session/tools.ts resolve()` → `session/llm/request.ts prepare()`

最终 system message（1-2 条）：

```
① 身份提示：agent.prompt（如 explore.txt）或厂商提示（anthropic.txt/gpt.txt/gemini.txt…）
② [env + instructions + mcp_instructions + skills] 拼接成一条
③ 用户消息级 system 覆盖（input.user.system）
最后可经 plugin 钩子 experimental.chat.system.transform 改写
```

各段来源：
- **env 块**：`<env>` 模型名 / cwd / workspace root / git repo? / platform / Today's date + `<available_references>`
- **instructions**：全局 `~/.config/opencode/AGENTS.md` → 项目级 findUp 只取第一个命中层级（AGENTS.md/CLAUDE.md/CONTEXT.md）；`config.instructions` 支持 glob/URL；Read 文件时上下文相关附加附近 AGENTS.md（每消息 claim 一次去重）
- **mcp_instructions**：`<mcp_instructions><server name="x">…</server></mcp_instructions>`
- **skills**：verbose 技能清单注入

**动态注入（Reminders）**：`session/reminders.ts` 在最后一条 user 消息追加 synthetic TextPart —— plan 模式注入 plan.txt、plan→build 注入 build-switch.txt；编辑器选区注入 `<system-reminder>`。

### 2. 消息 Part 模型（13 种 Part）

`Message = User | Assistant`，Assistant 含 tokens/cost/finish/path/model 等元数据。

| Part | 用途 |
|---|---|
| TextPart | 文本（synthetic/ignored 标记） |
| ReasoningPart | 思考过程 |
| ToolPart | 工具调用（四态状态机 pending→running→completed/error） |
| FilePart | 文件/图片附件（data:/file: URL，source 三源） |
| StepStart/StepFinishPart | 步骤快照（reason/cost/tokens） |
| SnapshotPart/PatchPart | git 快照/补丁 |
| AgentPart | @agent 引用 |
| SubtaskPart | 子任务指派 |
| CompactionPart | 压缩标记 |
| RetryPart | 重试记录 |

**ToolState 状态机**：`pending{input,raw} → running{input,title,metadata} → completed{output,title,metadata,attachments} | error{error}`。

### 3. 工具系统

**定义**（`tool/tool.ts`）：
```ts
Def<Parameters, M> = {
  id, description,            // 描述来自同名 .txt 文件
  parameters: Effect Schema,  // 字段 .annotate({description})
  execute(args, ctx),         // ctx: {sessionID, messageID, agent, abort, callID, messages, metadata(), ask()}
}
```

**注册**：InstanceState 一次性 init 内置 + 文件式（`{global,project}/opencode/{tool,tools}/*.ts` 动态 import）+ 插件表。

**按模型/agent 过滤**：websearch 按provider；gpt-5 系用 apply_patch 并剔除 edit/write；**task 工具的 description 动态拼入可用 subagent 列表**（"Available agent types: - explore: …"）。

**输出截断**（`tool/truncate.ts`）：默认 2000 行/50KB，超限保留 head/tail + 全文写盘（7 天保留）+ 提示"Use the Task tool to have explore agent process this file… Do NOT read the full file yourself"。

**图片管道**：tool-result 与用户图片统一走 `Image.Service.normalize()`（WASM resize 到模型限制内）。

**媒体兼容层**：`supportsMediaInToolResult()` 按 provider 拆分不支持的媒体，作为合成 user 消息注入（"Attached media from tool result:"）。

### 4. 权限系统（三层规则）

```
Rule = {permission, pattern, action: allow|deny|ask}
evaluate(): 对 agent.permission + session.permission + 本次会话 approved 逐 pattern 取 findLast 匹配
无匹配 → 默认 {action: "ask", pattern: "*"}
```

- **ask 流程**：逐 pattern evaluate → 任一 deny → DeniedError；全 allow → 通过；否则创建 Request 存 pending + 发事件 → Deferred 挂起等 reply
- **reply**：`once` 放行本次；`always` 写入 session approved 并自动放行其它 pending；`reject` 连带拒绝全部 pending（可带 feedback → CorrectedError 给模型反馈）
- **默认权限**：`*:allow`、`doom_loop:ask`、`plan 相关:deny`、`*.env*:ask`、external_directory 白名单
- **doom loop 检测**：连续 3 次同名同参工具调用 → 触发 doom_loop ask

### 5. Agent / 子 Agent 机制

**Agent.Info schema**：`{name, description, mode: subagent|primary|all, native, hidden, temperature, topP, color, permission(Ruleset), model, prompt, steps}`

内置 7 个：build（primary）、plan（primary，edit 全 deny 只能写 plans/*.md）、general（subagent）、explore（subagent 只读）、compaction/title/summary（hidden）。

**Task 工具执行流程**：
1. 深度检查（默认 depth 1，沿 parentID 上溯）
2. 权限 ask（permission:"task", patterns:[subagent_type]）
3. task_id 存在 → 复用子 session（否则新建）
4. 子 session 权限 = 父 deny 规则 + task:deny + todowrite:deny（防无限递归）
5. 子 agent 走完整独立 session 循环（自己的 system prompt、权限、事件流）
6. 返回 XML 包装：`<task id="{子sessionID}" state="completed"><summary>…</summary><task_result>…</task_result></task>`
7. **提示词级 trust 声明**：task.txt L17 "The agent's outputs should generally be trusted"
8. 后台模式：BackgroundJob 异步跑，完成时以 synthetic user part 注入父 session 并重新触发父循环；"DO NOT sleep, poll… You will be notified automatically"

**SubtaskPart 直调通道**：用户 `@agent-name` 直接执行，跳过模型决策，完成后追加 synthetic user "Summarize the task tool output above and continue"。

### 6. 会话管理

- **存储**：SQLite（drizzle）：SessionTable / MessageTable（data JSON）/ PartTable / TodoTable + 事件表（durable，aggregate: sessionID）
- **ID 体系**：`ses_/msg_/prt_` 前缀 ULID 有序
- **压缩**：历史序列化为 `[User]/[Assistant]/[Tool result]` 文本（工具输出截 2000 字符）→ LLM 摘要 → CompactionPart 替代旧历史；保留近 N token（默认 usable*0.25，clamp 2k~15k）
- **fork/revert**：基于 snapshot + diff 存储 revert 状态
- **自动标题**：第一条真实用户消息后用 title agent（小模型）生成 ≤100 字符
- **消息转模型消息**（`message-v2.ts`）：孤儿 tool 调用补 "[Tool execution was interrupted]"（Anthropic 要求 tool_use 必须有 result）

### 7. Agent Loop（核心循环）

```
prompt(input) → createUserMessage()（文件→Read转文本/PDF base64）
→ runLoop: while(true):
    1. 取历史（filterCompacted）
    2. 最后 assistant.finish 非 tool-calls 且 parent 对齐 → break
    3. step==1 → 生成标题 + summary
    4. 处理 subtask / compaction / token 溢出自动压缩
    5. SessionReminders.apply() 注入提醒
    6. 创建 assistant Message + processor
    7. SessionTools.resolve() 组装工具（含 MCP）
    8. json_schema 输出模式 → 注入 StructuredOutput + toolChoice=required
    9. llm.stream() → 事件流落库：
       text-delta → TextPart（打字机）
       tool-call → ToolPart pending→running（doom loop 检测）
       tool-result → 图片归一化 → completeToolCall
       step-finish → usage/cost 累计 + snapshot patch + 溢出检测
    10. finish: stop→break; compact→压缩; continue→下一轮
```

要点：AI SDK streamText 自带工具执行循环（execute 在 stream 内被调用），外层 while 负责"多 step 回合"（每 step 一次 LLM 调用）。

### 8. 模式系统（build/plan）

四层配合：**agent 权限差异**（plan 的 edit deny）+ **reminder 提示注入**（plan.txt "CRITICAL: Plan mode ACTIVE - READ-ONLY… ZERO exceptions"）+ **plan_exit 交接工具**（调用后弹 Question，Yes 则 synthetic user 消息切到 build）+ **UI 事件联动**（监听 part.updated 自动切 agent）。

### 9. LLM Provider 层

- 26 个 bundled provider 动态 import；模型元数据来自 models.dev
- `ProviderTransform`：按 provider 差异化参数/消息变换/schema 修补
- 双运行时：默认 AI SDK streamText；实验性原生运行时（直连协议）—— 两运行时输出同一种 LLMEvent 流
- **重试策略**：SessionRetry.policy 按 provider 定制（rate limit/5xx 退避），状态机发 retry 事件驱动 TUI 弹重试对话框
- **错误分类**：AuthError/APIError/ContextOverflowError/OutputLengthError/AbortedError/ContentFilterError（TUI 按类显示）

### 10. TUI 界面设计

**布局**：
```
┌────────────────────────────┬────────────┐
│ 消息流 scrollbox (sticky)  │ Sidebar    │
│  UserMessage（左边框+色）  │ 标题       │
│  AssistantMessage          │ workspace  │
│   ├ ReasoningPart（折叠）  │ todo/统计  │
│   ├ TextPart (markdown)    │            │
│   ├ ToolPart（分发表）     │            │
├────────────────────────────┴────────────┤
│ PermissionPrompt / SubagentFooter       │
│ Prompt 输入区（@文件 /命令 agent 补全） │
│ 状态栏：cwd | Perms LSP MCP /status     │
└─────────────────────────────────────────┘
```

**工具渲染分发**：ToolPart 按 toolDisplay switch 到 17 个专用组件；两种形态：InlineTool（单行 `-> Read src/foo.ts`）与 BlockTool（左边框面板：shell 输出/代码块+行号+Diagnostics）；输出默认折叠 3~10 行可展开。

**命令体系**：命令（namespace/name/title/category/slashName）→ 键绑定 → 模式栈三层抽象；leader key 超时、逗号前缀、alias；用户 keybinds 可覆盖。

**主题**：60 语义 token（primary/diffAdded/markdownHeading/syntaxKeyword/thinkingOpacity…）；主题 JSON 支持 defs/$ref/dark-light 变体；优先级 DEFAULT < plugin < custom < system（终端配色自动生成）。

---

## 三、全量 API 设计（18 组 HTTP API）

### 会话组 `/api/session`

| 方法 | 路径 | ID | 说明 |
|---|---|---|---|
| GET | /api/session | session.list | 分页列表（query: workspace/limit/order/search/directory/project/cursor） |
| POST | /api/session | session.create | 创建会话（payload: id/agent/model/location） |
| GET | /api/session/active | session.active | 本进程活跃前台会话 |
| GET | /api/session/:id | session.get | 会话详情 |
| POST | /api/session/:id/agent | session.switchAgent | 切换 agent |
| POST | /api/session/:id/model | session.switchModel | 切换模型 |
| POST | /api/session/:id/prompt | session.prompt | **发消息（入口）**：持久化输入 + 调度 agent-loop |
| POST | /api/session/:id/compact | session.compact | 手动压缩 |
| POST | /api/session/:id/wait | session.wait | 等待会话空闲 |
| POST | /api/session/:id/revert/stage | session.revert.stage | 暂存回退边界（可含文件恢复） |
| POST | /api/session/:id/revert/clear | session.revert.clear | 清除暂存 |
| POST | /api/session/:id/revert/commit | session.revert.commit | 提交回退 |
| GET | /api/session/:id/context | session.context | 压缩后有效上下文消息 |
| GET | /api/session/:id/history | session.history | 持久事件分页读取 |
| GET | /api/session/:id/event | session.events | SSE 订阅（after 序号回放 + 实时） |
| POST | /api/session/:id/interrupt | session.interrupt | 中断执行 |
| GET | /api/session/:id/message/:msgID | session.message | 单条消息 |

### 消息组
| GET | /api/session/:id/message | session.messages | 消息列表（分页） |

### 权限组
| GET | /api/permission/request | permission.request.list | 全局待审批请求 |
| GET | /api/permission/saved | permission.saved.list | 已保存规则 |
| DELETE | /api/permission/saved/:id | permission.saved.remove | 删除规则 |
| POST | /api/session/:id/permission | session.permission.create | 追加会话权限 |
| GET | /api/session/:id/permission | session.permission.list | 会话权限列表 |
| GET | /api/session/:id/permission/:reqID | session.permission.get | 权限请求详情 |
| POST | /api/session/:id/permission/:reqID/reply | session.permission.reply | 回复（once/always/reject + feedback） |

### 问答组
| GET | /api/question/request | question.request.list | 全局待答问题 |
| GET | /api/session/:id/question | session.question.list | 会话问题列表 |
| POST | /api/session/:id/question/:reqID/reply | session.question.reply | 回答 |
| POST | /api/session/:id/question/:reqID/reject | session.question.reject | 拒答 |

### 其他组
| 方法 | 路径 | ID | 说明 |
|---|---|---|---|
| GET | /api/agent | agent.list | agent 列表（含 subagent、工具权限） |
| GET | /api/command | command.list | 命令/斜杠命令列表 |
| GET | /api/skill | skill.list | 技能列表 |
| GET | /api/provider | provider.list | provider 列表 |
| GET | /api/provider/:id | provider.get | provider 详情 |
| GET | /api/model | model.list | 模型列表（含 cost/limit/modality） |
| GET | /api/location | location.get | 当前 location 解析 |
| PATCH | /api/credential/:id | credential.update | 更新凭证 |
| DELETE | /api/credential/:id | credential.remove | 删除凭证 |
| GET | /api/integration | integration.list | 集成列表 |
| GET | /api/integration/:id | integration.get | 集成详情 |
| POST | /api/integration/:id/connect/key | integration.connect.key | API key 连接 |
| POST | /api/integration/:id/connect/oauth | integration.connect.oauth | OAuth 连接 |
| GET | /api/integration/attempt/:id | integration.attempt.status | OAuth 状态轮询 |
| POST | /api/integration/attempt/:id/complete | integration.attempt.complete | 完成连接 |
| DELETE | /api/integration/attempt/:id/cancel | integration.attempt.cancel | 取消连接 |
| GET | /api/fs/read/* | fs.read | 读文件（路径通配） |
| GET | /api/fs/list | fs.list | 列目录 |
| GET | /api/fs/find | fs.find | 查找文件 |
| GET | /api/pty | pty.list | 终端列表 |
| POST | /api/pty | pty.create | 创建终端 |
| GET | /api/pty/:id | pty.get | 终端详情 |
| PUT | /api/pty/:id | pty.update | 更新终端 |
| DELETE | /api/pty/:id | pty.remove | 删除终端 |
| POST | /api/pty/:id/connect-token | pty.connectToken | 终端连接令牌 |
| GET | /api/pty/:id/connect | pty.connect | WebSocket 终端流 |
| GET | /api/reference | reference.list | 参考库列表 |
| GET | /api/health | health.get | 健康检查 |
| GET | /api/event | event.subscribe | 全局 SSE 事件流 |

## 四、全量事件清单（Event Manifest）

事件模型：`{id, type, timestamp, sessionID?, location?, metadata?, durable?{aggregateID, seq, version}}`
**durable 事件**按 sessionID 聚合持久化（event 表），支持 SSE 回放；**live 事件**仅实时广播。

### 1. 会话核心事件（session.next.*，全部 durable）
| 事件 | 载荷要点 | 时机 |
|---|---|---|
| session.next.agent.switched | messageID, agent | agent 切换 |
| session.next.model.switched | messageID, model | 模型切换 |
| session.next.moved | location, subdirectory | 会话移动 |
| session.next.prompted | messageID, prompt, delivery | 用户消息提交 |
| session.next.prompt.admitted | messageID, prompt, delivery | 输入入队成功 |
| session.next.context.updated | messageID, text | 上下文更新 |
| session.next.synthetic | messageID, text | 合成消息注入（reminders/后台任务完成） |
| session.next.shell.started / ended | callID, command/output | shell 工具执行 |
| session.next.step.started | assistantMessageID, agent, model, snapshot | 一步开始 |
| session.next.step.ended | finish, cost, tokens{input,output,reasoning,cache{read,write}}, files | 一步结束（含快照） |
| session.next.step.failed | error | 一步失败 |
| session.next.text.started / delta(live) / ended | textID, delta?, text | 文本流 |
| session.next.reasoning.started / delta(live) / ended | reasoningID, providerMetadata | 思考流 |
| session.next.tool.input.started / delta(live) / ended | callID, name, delta?, text | 工具参数流 |
| session.next.tool.called | callID, tool, input, provider{executed,metadata} | 工具调用确认 |
| session.next.tool.progress | callID, structured, content[] | 工具运行中间态 |
| session.next.tool.success | callID, structured, content[], outputPaths?, result? | 工具成功 |
| session.next.tool.failed | callID, error, result? | 工具失败 |
| session.next.retried | attempt, error{message,statusCode,isRetryable,...} | 请求重试 |
| session.next.compaction.started / delta(live) / ended | messageID, reason(auto/manual), text, recent | 压缩 |
| session.next.revert.staged / cleared / committed | revertState / - / messageID | 回退 |

### 2. 会话状态（live）
| 事件 | 说明 |
|---|---|
| session.status | busy{type} / retry{attempt,message,action,next} / idle |
| session.idle | 会话空闲 |
| session.compacted | 压缩完成（旧 API） |

### 3. 权限/问答（live）
| 事件 | 说明 |
|---|---|
| permission.asked / permission.replied | 权限请求/回复 |
| question.asked / question.replied / question.rejected | 问答请求/回复/拒答 |

### 4. 文件系统与外部（live）
| 事件 | 说明 |
|---|---|
| fs.edited | 文件编辑（含 diff） |
| fsWatcher.updated | 文件监听变化 |
| pty.created / updated / exited / deleted | 终端生命周期 |
| lsp.updated | LSP 诊断更新 |
| mcp.tools.changed / mcp.browser.open.failed | MCP 变化 |
| vcs.branch.updated | git 分支变化 |
| worktree.ready / worktree.failed | worktree 就绪/失败 |
| workspace.ready / workspace.failed / workspace.status | 工作区状态 |
| project.updated / projectDirectories.updated | 项目/目录更新 |
| reference.updated | 参考库更新 |

### 5. 平台基础（live）
| 事件 | 说明 |
|---|---|
| server.connected / global.disposed | 服务器连接/销毁 |
| plugin.added / installation.updated / installation.updateAvailable | 插件/安装更新 |
| catalog.updated / modelsDev.refreshed / integration.updated / integration.connectionUpdated | 目录/模型/集成更新 |
| tui.prompt.append / tui.command.execute / tui.toast.show / tui.session.select | TUI 控制指令 |

## 五、服务层详细设计（Effect-TS Service/Layer）

| 服务 | 文件 | 职责细节 |
|---|---|---|
| Global | core/src/global.ts | XDG 路径管理：data/cache/config/state/tmp/bin/log/repos；启动时自动建目录 |
| Config | core/src/config.ts | 分层配置合并：opencode.json（schema 校验），环境变量覆盖 |
| Provider | core/src/provider.ts | 26 个 bundled provider 动态 import；models.dev 元数据热更新；custom provider 自动发现 |
| LLM | opencode/src/session/llm.ts | 双运行时：AI SDK streamText（默认）/ 原生协议直连；wrapSSE 逐 chunk 超时保护；LLMEvent 归一化 |
| SessionPrompt | opencode/src/session/prompt.ts | **Agent Loop 核心**：runLoop 状态机（见下） |
| Processor | opencode/src/session/processor.ts | LLM 流事件 -> Part 落库 + 事件发布；doom loop 检测；快照/成本累计 |
| SessionTools | opencode/src/session/tools.ts | 工具 resolve（内置+自定义+MCP+插件过滤）；ProviderTransform.schema 适配 |
| ToolRegistry | opencode/src/tool/registry.ts | 内置工具 init；文件式/插件式注册；按 agent/model 过滤 |
| Permission | opencode/src/permission/index.ts | 规则求值（findLast）；ask/reply Deferred 挂起；session approved 累积 |
| Agent | opencode/src/agent/agent.ts | Agent.Info 定义（7 内置+用户 config 扩展）；generate LLM 生成 |
| Instruction | opencode/src/session/instruction.ts | AGENTS.md findUp 发现；上下文相关加载（claims 去重） |
| Snapshot | core/src/snapshot.ts | git 快照/patch 生成，session_diff 存储 |
| Compaction | opencode/src/session/compaction.ts | 历史序列化->摘要->CompactionPart；preserve_recent_tokens 保留 |
| Revert | opencode/src/session/revert.ts | 回退状态机（stage/clear/commit） |
| Share | opencode/src/share/ | 会话上传与分享 URL |
| EventV2Bridge | opencode/src/event-v2-bridge.ts | 内部事件 -> server SSE |
| PTY | core/src/pty.ts | 终端进程管理 + WebSocket 桥 |
| Skill | core/src/skill.ts | 技能发现（directory/url/embedded） |
| Reference | core/src/reference.ts | 参考库（local/git） |
| Integration | core/src/integration.ts | 集成连接（oauth/key/env）+ attempt 状态机 |
| Credential | core/src/credential.ts | 凭证存储（oauth/key） |
| BackgroundJob | core/src/background-job.ts | 后台子代理任务 |
| Image | core/src/image.ts | WASM 图片归一化（resize） |
| ToolOutputStore | core/src/tool-output-store.ts | 截断输出落盘管理（7 天清理） |

**Agent Loop 状态机**（session/prompt.ts runLoop）：
```
prompt(input) -> createUserMessage() -> state.ensureRunning(runLoop)
runLoop: while(true)
  ├─ 取历史（filterCompacted）
  ├─ 终止检查：最后 assistant.finish != tool-calls -> break
  ├─ step==1 -> 自动标题 + summary
  ├─ subtask -> handleSubtask（@agent 直调通道）
  ├─ token 溢出 -> compaction.create(auto)
  ├─ SessionReminders.apply()（plan/build-switch 注入）
  ├─ 创建 assistant Message + Processor
  ├─ SessionTools.resolve() 组装工具
  ├─ llm.stream() -> Processor 事件投影落库
  └─ finish: stop->break | compact->压缩 | continue->下一轮
```

## 六、存储设计

### 1. 表设计（SQLite + drizzle，库文件 `~/.local/share/opencode/opencode.db`，WAL 模式）

| 表 | 字段 | 说明 |
|---|---|---|
| session | id(PK), project_id(FK), workspace_id, parent_id, slug, directory, path, title, version, share_url, summary_additions/deletions/files, summary_diffs(JSON), metadata(JSON), cost, tokens_input/output/reasoning/cache_read/cache_write, revert(JSON), permission(JSON), agent, model(JSON), time_created/updated, time_compacting, time_archived + 3 索引 | 会话主表（含成本/token 统计与回退状态） |
| message | id(PK), session_id(FK cascade), data(JSON: V1MessageData), time_created/updated + 索引(session_id,time_created,id) | 消息（JSON 存元数据） |
| part | id(PK), message_id(FK cascade), session_id, data(JSON: V1PartData), time_created/updated + 2 索引 | 消息 Part（13 种类型） |
| todo | (session_id, position) PK, content, status, priority, position, time_created/updated | Todo 清单 |
| session_message | id(PK), session_id(FK), type, seq, data(JSON), time_created/updated + 4 索引（含 (session_id,seq) 唯一） | V2 投影消息（事件溯源投影） |
| session_input | id(PK), session_id(FK), prompt(JSON), delivery, admitted_seq, promoted_seq, time_created + 4 索引（admitted/promoted seq 唯一） | 输入收件箱（durable admit + promote） |
| session_context_epoch | session_id(PK), baseline, snapshot(JSON), baseline_seq | 上下文纪元（压缩基线） |
| event_sequence | aggregate_id(PK), seq, owner_id | 事件序列号分配 |
| event | id(PK), aggregate_id(FK), seq, type, data(JSON) + 2 索引（(aggregate_id,seq) 唯一） | 持久事件（durable events） |
| project | id(PK), vcs, ... | 项目 |
| project_directory | (project_id, path) PK | 项目多目录 |
| workspace | id, ... | 工作区 |
| credential | id, type(oauth/key), ... | 凭证 |
| permission_saved | id, ... | 已保存权限规则 |
| session_share | id, ... | 分享记录 |
| account / account_state / control_account | ... | 账户体系 |
| data_migration | id, ... | 数据迁移状态 |

### 2. 文件系统设计

```
~/.local/share/opencode/          # XDG data
├── opencode.db                   # 主库（SQLite WAL）
├── log/                          # 日志
├── repos/                        # 克隆仓库缓存
├── snapshot/                     # git 快照（按项目 hash/快照 hash 两级）
│   └── {projectHash}/{snapshotHash}/...
├── tool-output/                  # 截断工具输出全文（tool_<ulid>，7 天保留）
└── storage/
    └── session_diff/             # 会话 diff 文件（旧版兼容）

~/.config/opencode/               # XDG config
├── opencode.json                 # 全局配置（provider/model/agent/permission/tool_output...）
├── AGENTS.md                     # 全局指令
├── package.json                  # 插件依赖
└── node_modules/                 # 插件

项目内：
├── .opencode/
│   ├── agent/                    # 项目级 agent 定义
│   ├── command/                  # 项目级命令
│   ├── tool/ 或 tools/           # 项目级工具（*.ts 动态 import）
│   ├── plugin/                   # 项目级插件
│   └── plans/                    # plan 模式计划文件（plan agent 唯一可写目录）
├── AGENTS.md                     # 项目指令（findUp 第一个命中生效）
├── CLAUDE.md                     # 兼容
└── CONTEXT.md                    # 废弃兼容
```

**PRAGMA 配置**：journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, cache_size=-64000, foreign_keys=ON。

## 七、对 AI 工作台的借鉴要点

1. **Part 级消息模型** —— 流式输出拆成可持久化 part，delta 与全量分离，天然支持回放/分支/压缩
2. **权限三层规则** —— permission+pattern+action，findLast 优先，session approved 运行时累积
3. **子 agent = 独立 session + task_id 续传 + XML 包装结果 + 提示词级 trust 声明 + 权限继承（deny 下传）**
4. **工具输出统一截断管道**（写盘 + 引导委托 explore 处理）+ provider 媒体兼容层
5. **Plan 模式 = 权限裁剪 + reminder 提示 + plan_exit 交接工具 + UI 事件联动**
6. **命令=键位=slash 三位一体**、mode 栈、主题 token 化
7. **双进程模型**：server 承载核心逻辑，TUI 纯前端事件驱动 —— 便于 Web 工作台复用同一套核心
8. **事件溯源双通道**：durable 事件（sessionID 聚合 + seq）落库可回放，live 事件只广播 -- 会话恢复 = 重放 durable 事件投影
9. **输入收件箱模式**（session_input 表 admitted_seq/promoted_seq）-- 用户输入先持久化再消费，天然支持排队与崩溃恢复
10. **上下文纪元**（session_context_epoch）-- 压缩后新基线，明确有效上下文边界
