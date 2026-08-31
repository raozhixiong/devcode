# Lobster 龙虾工作台

企业级多角色 AI 工作台：OpenClaw 的功能形态 + OpenCode 的编码能力，单体 Java 应用，本地启动即用。

- 技术栈：Java 21 + Spring Boot 3.3.5 + SQLite（WAL），虚拟线程，WebSocket 帧协议
- 端口：**18790**（`http://127.0.0.1:18790`）
- 状态目录：`~/.lobster`（`lobster.state-dir` 可覆盖）

## 快速开始

```powershell
mvn package "-DskipTests" -q
java -jar target\lobster-gateway-0.1.0-SNAPSHOT.jar
# 浏览器打开 http://127.0.0.1:18790
```

首次启动为 Mock 模式（不调真实模型，仅 echo 文案）。接真实 LLM 见下文。

## 目录结构

```
com.lobster
├── agent       AgentLoop（状态机循环）、PromptAssembler、HookEngine、QueueMode、PlanMode
├── workboard   DispatchService（自动派发）、LifecycleSyncService、NotificationService
├── event       EventBus（durable SQLite + live WebSocket 双通道）
├── llm         LlmProvider、OpenAiCompatProvider（SSE 流式）、MockLlmProvider
├── model       Part sealed 接口、Message、Session
├── permission  PermissionEngine（findLast 规则匹配，ASK 挂起最多 30 秒）
├── store       各存储（共享库 + 每 agent 库，两套 Flyway）
├── tool        Tool SPI + 内置工具（read/write/edit/glob/grep/bash/todo/question/board.* 等）
├── ws          WsHandler、RuntimeConfig、ChannelReplyService、WebhookController
└── util        Ulid、StateDirs
```

## 接入真实 LLM

在 `~/.lobster/lobster.json` 写入 `llm` 段（含 `baseUrl` 与 `apiKey` 即生效，OpenAI 兼容协议）：

```json
{
  "llm": {
    "provider": "openai-compat",
    "baseUrl": "https://api.example.com/v1",
    "apiKey": "sk-xxxxxx",
    "model": "gpt-4o-mini",
    "temperature": 0.7,
    "contextLimit": 128000
  }
}
```

- `provider`：`openai-compat`（默认，任何 OpenAI 兼容端点：OpenAI / DeepSeek / GLM / 通义 / 本地 vLLM 均可）
- `baseUrl`：兼容端点根地址，需带 `/v1`（或对应版本前缀）
- `model`：模型 ID
- 未配置或缺少 `baseUrl`/`apiKey` 时自动回退 Mock 模式
- 配置文件路径可用启动参数覆盖：`-Dlobster.config=path/to/lobster.json`

### worker 权限规则（自动派发需要）

看板自动派发的 worker 会话会调用 `board.complete` / `board.block` 等工具，默认权限引擎对未知工具走 ASK（挂起 30 秒后拒绝）。建议在 `lobster.json` 追加：

```json
{
  "permissions": [
    { "permission": "board", "pattern": "*", "action": "ALLOW" }
  ]
}
```

> 规则三元组 `permission / pattern / action(ALLOW|DENY|ASK)`，`findLast` 语义（后写的优先）。

## 企微/钉钉/飞书频道接入

### 1. 绑定频道

方式 A（界面）：**频道** 视图 →「+ 绑定频道」→ 选 `wecom`，填账号 ID，配置里填出站 webhook。

方式 B（RPC）：

```json
{
  "method": "channels.bindings.create",
  "params": {
    "channel": "wecom",
    "accountId": "team-a",
    "agentId": "main",
    "config": "{ \"outboundUrl\": \"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxx\" }"
  }
}
```

`config.outboundUrl` 即该平台群机器人的 **Webhook 地址**：

| 平台     | outboundUrl 获取方式 | 消息体格式 |
| -------- | -------------------- | ---------- |
| 企微     | 群设置 → 群机器人 → 添加 → Webhook 地址 | `{"msgtype":"text","text":{"content":...}}` |
| 钉钉     | 群设置 → 智能群助手 → 自定义机器人（安全设置选“自定义关键词”或 IP 白名单） | `{"msgtype":"text","text":{"content":...}}` |
| 飞书     | 群设置 → 群机器人 → Custom Bot → Webhook 地址 | `{"msg_type":"text","content":{"text":...}}` |

> 钉钉机器人如启用“加签”，当前版本未实现签名计算，建议先用关键词/IP 白名单方式。

### 2. 入站（可选）：把群消息接给 agent

将平台回调指向 `POST /webhook/{channel}/{accountId}`（如企微回调 URL 配 `http://你的主机:18790/webhook/wecom/team-a`），消息会以 `channel:wecom:team-a` 会话 key 进入 agent；agent 回答后自动回发群里。

### 3. 看板通知推送到群

看板通知支持订阅分发，target 为 `channel:<channel>:<accountId>`：

- 界面：看板卡片抽屉 →「🔔 订阅」→ 填 `channel:wecom:team-a`
- RPC：`{"method": "workboard.subscribe", "params": {"cardId": "crd_xxx", "target": "channel:wecom:team-a"}}`
- 整板订阅（不传 cardId 传 boardId）：`{"params": {"boardId": "main", "target": "channel:wecom:team-a"}}`

此后该卡片（或该板所有卡片）的认领/完成/阻塞/解除等事件都会推送到企微群。

## 常用命令

```powershell
mvn test                                  # 全量测试（约 2 分钟，timeout 给足 600s）
mvn test "-Dtest=WsHandlerTest"           # 单个测试类（-Dtest 必须加引号）
mvn package "-DskipTests" -q              # 打 jar
node --check src/main/resources/static/app.js   # 前端语法检查
```

## 架构要点（速览）

- **消息流**：WS `chat.send` → MessageStore 落库 → AgentLoop（虚拟线程）→ LlmProvider → 工具执行 → EventBus → WS 推送
- **看板调度**：`DispatchService` 每 30s 扫 `READY` 卡 → claim → 起 worker 子会话（并发≤3）；worker 结束未显式 complete/block 判协议违例自动阻塞；心跳过期 stale 收割；阻塞卡可由 LLM 自动拆分子任务
- **依赖联动**：子任务全完成→父卡自动完成；被依赖卡完成且依赖满足→依赖方自动解除阻塞；某卡阻塞→依赖方级联阻塞
- **通知**：卡片事件 → 通知中心（🔔 铃铛）+ `workboard.notification` live 事件 + 按订阅外发渠道

更多设计细节见 `requirement/` 与 `docs/superpowers/plans/`。
