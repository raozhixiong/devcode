# AGENTS.md - Lobster 龙虾工作台仓库指南

## 项目定位

企业级多角色 AI 工作台（对标 OpenClaw 的功能形态 + OpenCode 的编码能力）。
Java 21 + Spring Boot 3.3.5 单体应用 `lobster-gateway`，SQLite 存储，本地启动，端口 **18790**。

- 需求/设计文档（权威，后续里程碑依据）：`requirement/03~07-龙虾-*.md`
- 已验收里程碑计划：`docs/superpowers/plans/2026-08-25-lobster-m1-kernel.md`（M1 内核已完成）

## 常用命令

```powershell
mvn test                                  # 全量测试（约 2 分钟，建议 timeout 600000ms）
mvn test "-Dtest=WsHandlerTest"           # 单个测试类（PowerShell 必须给 -Dtest 加引号）
mvn package "-DskipTests" -q              # 打 jar（target/lobster-gateway-0.1.0-SNAPSHOT.jar）
java -jar target\lobster-gateway-0.1.0-SNAPSHOT.jar   # 启动，浏览器开 http://127.0.0.1:18790
```

## 架构要点

```
com.lobster
├── agent       AgentLoop（状态机循环, MAX_STEPS=50）、PromptAssembler
├── event       EventBus（durable SQLite + live WebSocket 双通道）、Events 常量
├── llm         LlmProvider 接口、OpenAiCompatProvider（SSE 流式）、MockLlmProvider
├── model       Part sealed 接口（8 种子类型, Jackson @JsonSubTypes）、Message、Session
├── permission  PermissionEngine（findLast 规则匹配, ASK 挂起最多 30 秒）
├── store       DatabaseConfig（共享库）、AgentDb（每 agent 独立库）、MessageStore
├── tool        Tool SPI + 9 内置工具（read/write/edit/glob/grep/bash/todo/question/ls）
├── ws          WsHandler（帧协议 req/res/event）、RuntimeConfig（Bean 装配+Mock LLM+权限规则）
└── util        Ulid（单调）、StateDirs（~/.lobster 目录布局）
```

- 消息流：WS `chat.send` → MessageStore 落库 → AgentLoop（虚拟线程）→ LlmProvider → 工具执行 → EventBus 发布 → WS 推送
- 事件名常量在 `Events.java`，勿硬编码字符串

## 关键约束（勿破坏）

- `application.yml` 必须排除 `DataSourceAutoConfiguration` **和** `FlywayAutoConfiguration`：DataSource/Flyway 全部由 `DatabaseConfig` 手动装配（共享库+agent 库两套迁移，自动配置会扫到重复的 V1 导致启动失败）
- DB pragmas：WAL、busy_timeout=5000、foreign_keys=ON
- 状态目录 `~/.lobster`（`lobster.state-dir` 可覆盖）；共享库 `lobster.db` + 每 agent 库 `agents/<id>/agent/<id>.db`，两套 Flyway 脚本在 `db/shared/` 与 `db/agent/`
- 默认 LLM 是 Mock（`RuntimeConfig`）；接真实 LLM 时换 OpenAiCompatProvider，配置从 `lobster.json` 读

## 已知坑

- **javadoc 内不能出现 `**/`**（会提前闭合注释导致编译错）
- **PowerShell `Set-Content` 写 .java 会加 UTF-8 BOM** → javac 报"非法字符 \ufeff"。用 `[IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($false)))`
- **mvn 经常超 120 秒默认超时**：给 bash 工具设 timeout 600000；挂起时查 `target/surefire-reports/*.txt`
- **权限测试**：规则未命中 ALLOW/DENY 时 PermissionEngine.ask 挂起 30 秒，测试必须显式配规则或用 ASK 超时路径
- **MockLlmProvider**：单脚本构造器每轮重复同一脚本；多轮循环用 `MockLlmProvider.ofTurns(List<List<LlmEvent>>)`
- Java glob `**/*.x` 不匹配根级文件（GlobTool 有 loose matcher 兜底）；相对路径需把 `\` 归一为 `/`
- git 仓库根即项目根；分支 `feature/lobster-m1-kernel` 已合并推送

## 测试约定

- 每个模块一个测试类，集成测试用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@TestPropertySource(lobster.state-dir=target/test-state-xxx)` 隔离状态目录
- WebSocket 测试用 `WebSocketClient`，断言事件序列（admitted → step.started → delta → ended → idle）
- 无 lint/typecheck 命令，`mvn test` 即验收

## 未完成路线（优先级从高到低）

1. **M2 内核补全**：真实 LLM 接入、权限 ask 走 WS、PromptAssembler 完整版（cache 边界/AGENTS.md）、上下文压缩、子代理 task 工具、Plan 模式、输入收件箱 admit/promote、writer claim 围栏、doom loop 检测、事件恢复 SSE
2. **M3 多角色**：8 角色 RBAC、队列模式 steer/followup、会话 fork/rewind、三层归属、状态感知
3. **M4 流程平台**：任务台账、Workboard 看板、Cron 调度、五层记忆+Dreaming、Usage 统计、Skills
4. **M5 企业化**：频道接入（webhook/企微/钉钉/飞书）、审批中心、Docker 沙箱、审计、认证（当前 WS 免鉴权）、配置中心+插件
5. **前端**：三栏工作台、类型感知工具卡片（diff/折叠）、会话树、斜杠命令/@补全/⌘K

详细差距对照见 `requirement/03-龙虾-全量功能需求.md`。
