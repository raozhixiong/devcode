# OpenClaw 功能清单与核心设计全解

> 来源：`D:\workspace\sourcecode\openclaw-main` 深度研究报告
> 用途：作为 AI 工作台（多角色工作台、频道、任务系统）的参考蓝本

---

## 一、产品定位与整体架构

### 1. 产品定位
OpenClaw 是**多 agent 个人助理平台**：一个常驻 Gateway 守护进程连接所有消息渠道（WhatsApp/Telegram/Slack/Discord/Signal/iMessage/WebChat...），多角色 agent（多 persona 隔离）通过频道与人交互，支持子 agent、任务、cron 自动化、记忆系统、沙箱执行、浏览器控制 UI。

### 2. 整体架构

```
┌────────────────────────────────────────────────────────────┐
│ 客户端层                                                    │
│  Control UI (Vite+Lit SPA, :18789) │ macOS菜单栏 │ CLI │     │
│  移动 Nodes (iOS/Android, role=node) │ WebChat │ ACP 客户端 │
├────────────────────────────────────────────────────────────┤
│ Gateway（守护进程，WebSocket :18789，唯一控制面）              │
│  ├─ WS 协议：connect 握手(挑战/设备签名/配对) + req/res/event │
│  ├─ 频道层：Baileys(WhatsApp)/grammY(Telegram)/Slack/Discord │
│  ├─ 会话路由：sessionKey 路由(main/per-group/cron/hook)      │
│  ├─ 命令队列：lane FIFO（session lane + global lane 并发帽）  │
│  └─ RPC 方法族：chat/sessions/agent/cron/tasks/approvals...  │
├────────────────────────────────────────────────────────────┤
│ Agent 运行时（嵌入式）                                       │
│  AgentSession（树）→ Agent loop → 工具系统 → 沙箱执行          │
│  多 agent：每 agent 独立 workspace/状态目录/会话库             │
├────────────────────────────────────────────────────────────┤
│ 存储                                                        │
│  共享状态库 ~/.openclaw/state/openclaw.sqlite               │
│  每 agent 库 ~/.openclaw/agents/<id>/agent/openclaw-agent.sqlite │
│  workspace 明文文件（AGENTS/SOUL/IDENTITY/USER/MEMORY.md）    │
│  managed worktrees <stateDir>/worktrees/<repo指纹>/<名称>     │
└────────────────────────────────────────────────────────────┘
```

## 二、功能清单

### 1. 多角色 Agent 体系
- **Agent = 完整个人格域**：独立 workspace、bootstrap 文件（AGENTS.md 操作指令/SOUL.md 人格/IDENTITY.md 名字与表情/USER.md 用户画像/BOOTSTRAP.md 首次仪式/MEMORY.md 长期记忆）、独立 auth profiles、独立模型注册表、独立会话库
- **Binding 路由**：频道账号（某 Slack workspace/某 WhatsApp 号码）绑定到某 agent，入站消息经 binding 路由
- `openclaw agents add work` 添加 agent；agentId 默认 `main`
- 会话键：`agent:<agentId>:<mainKey>`；全局 scope 时为 `global`
- **生命周期**：agent 心跳（heartbeat cron 自我维护）、系统 agent（kind=system）、agent 添加/更新/删除（`agents.*` RPC）

### 2. 会话与消息
- **路由规则**：DM 默认共享 main session；群组/房间默认隔离；cron 每次新会话；webhook 每 hook 隔离
- `session.dmScope`（main/per-peer/per-channel-peer/per-account-channel-peer）与 `session.groupScope` 可调
- **队列模式**（同一 session 有活跃 run 时新消息行为）：`steer`（注入活跃运行，默认）/ `followup`（排队后续轮）/ `collect`（合并为单轮）/ `interrupt`（中止后跑最新）；500ms debounce、cap 20、drop summarize
- **三层层主归属**：Creator（不可变）/ Owner（可指派，类 GitHub assignee）/ Participants（≤32 参与者历史）
- 会话 fork、归档（先围栏后提交）、重置、删除、压缩、贴图钉、图标、自定义分组
- **writer claim 围栏**：每个 run 持久化 activeWriterRunId，所有 transcript 提交必须带 expectedWriterRunId 校验，防止过期 run 写入旧数据
- **状态感知**：durable 状态变化信号日志（stateVersion + changesSince 事件），watcher 收到合并通知

### 3. 子 Agent 与多 Agent 协作
- `sessions_spawn`：非阻塞生成隔离子会话；返回 runId + childSessionKey；可选 model/thinking/超时/线程绑定/沙箱强制/上下文 fork/可见（dashboard 会话）
- 完成后 announce 步骤推送结果到请求者频道（线程路由保留）
- `sessions_yield`：主动结束当前轮，等子 agent 结果作为下一条消息（避免轮询）
- `subagents` 工具：列出/取消会话树内的后台工作
- maxSpawnDepth≥2 时 depth-1 编排型子 agent 额外获得 sessions_spawn/subagents/sessions_list/sessions_history
- **跨会话通信**：sessions_send（可选等待）、conversations_send/turn（外部精确地址）、message 工具；A2A 回环（REPLY_SKIP 早停）
- **可见性分级**：self/tree/agent/all（默认 tree）；沙箱调用者限 spawn 子树

### 4. 任务与自动化
- **任务台账（Task Registry）**：统一任务记录（见后表设计），运行时族 subagent/acp/cron/cli，状态机 queued→running→succeeded/failed/timed_out/cancelled/lost，投递状态（pending/delivered/session_queued/failed/dismissed/parent_missing/not_applicable），通知策略（done_only/state_changes/silent）
- **Workboard 看板插件**：卡片状态（triage/backlog/todo/scheduled/ready/running/review/blocked/done）、优先级、指派 agent、链接 task/run/session、执行元数据、事件历史（created/edited/moved/claimed/attempt_started...）
- **Cron**：cron.get/list/status/add/update/remove/run/runs；heartbeat 心跳自我维护；wake 唤醒注入
- **托管 worktree**：`<stateDir>/worktrees/<repo指纹>/<名称>`，分支 `openclaw/<name>`，快照后清理；`.worktreeinclude` 复制忽略文件；`.openclaw/worktree-setup.sh` 仓库级初始化钩子

### 5. 记忆系统（五层信任模型）
| 层 | 载体 | 写入者 | 注入 |
|---|---|---|---|
| Instructions | AGENTS.md 等指令文件 | 仅人类 | 每次会话开始 |
| Curated core | MEMORY.md、USER.md | Dreaming 整合/用户直接要求 | 每次会话开始（预算内） |
| Episodic | memory/YYYY-MM-DD.md、transcripts | agent 工作中/记忆冲刷 | 从不；按需检索 |
| Prospective | standing intents（SQLite）、cron | intent 工具 | 触发时 |
| Review | DREAMS.md、dreaming 报告 | Dreaming 阶段 | 从不（人读） |
- **来源溯源（provenance）**：owner（可信）/transcript（不可信）等 origin class 存为 SQLite 列，模型无法通过散文写入；晋升 curated 必须过确定性闸门
- **Dreaming**：后台整合扫描，把日常笔记蒸馏进 MEMORY.md
- memory_search/memory_get 工具按需检索（FTS5 + 可选向量）
- **bootstrap 预算**：单文件 20000 字符、总计 60000 字符，截断注入提示

### 6. 沙箱与安全
- **沙箱三设置**：mode（off/non-main/all）、scope（agent/session/shared）、backend（docker/podman/ssh/openshell）
- 沙箱内执行：exec/read/write/edit/apply_patch/process/浏览器；Gateway 本体永不在沙箱
- elevated 提权路径（tools.elevated）可逃逸沙箱
- **exec 审批**：exec.approval.* 一致审批注册表；策略快照 exec.approvals.get/set
- **设备配对**：connect 挑战签名（v3 绑定 platform/deviceFamily）+ device token；loopback 自动批准；scope 升级需显式批准
- **密钥**：secrets.store.*（值不可回读）；SecretRef 解析
- 审计台账（metadata-only，无 prompt/消息体）

### 7. 界面（Control UI）——前端全量功能

**技术栈**：Vite + Lit SPA，Gateway 直接托管（`/`，可配 basePath），同端口 WebSocket；主题 Claw/Knot/Dash 内置 + tweakcn 导入槽（浏览器本地）；文字大小/消息宽度可调；多语言 i18n；PWA + Web Push。

**全局框架**：
- 顶栏控制簇：搜索按钮（⌘K 命令面板：跨 agent 线程搜索 + 导航命令）、新建会话、连接状态
- 侧边栏（围绕 agent 组织）：身份行（当前 agent 头像/名字，点击切换）-> Pages 区（Home=main session 带未读/运行徽章；可自定义 pin 入口，默认 Automations+Plugins；customize 菜单列出所有目的地含 Usage 与插件页）-> 会话分区：Pinned / 自定义分组（category，可折叠拖拽排序，gateway 同步） / Other（未分组）/ Groups（群组）/ Coding（worktree/exec-node 绑定会话，显示 repo⎇branch+node，ACP/Codex/Claude CLI 目录，默认折叠记忆选择）
- 会话工具栏：过滤排序（Created/Last updated/People）、Group by（自定义组/频道/种类/agent/日期）、状态过滤（Active/Archived/All）、+ 新会话
- 会话行状态体系：未读点 / Queued+沙漏（等并发槽）/ 铅笔（未发草稿）/ globe（云 worker）/ 展开子会话树（父行 disclosure+child count）/ owner pair-stack 头像 / ringed 在线观众头像 / agent 状态声明（琥珀色 attention 图标 TTL）
- 会话行上下文菜单：Pin/Unpin、标记已读未读、重命名、指派给我/指派给…、设图标（emoji+6 命名图标）、Fork、复制会话 ID、移动分组（含新建组/移出组）、归档/恢复、删除；Cmd/Ctrl 多选 + Shift 范围选 -> 批量操作（已读/移动/归档/删除）；拖拽到 Pinned/分组
- 设置导航（Settings 分组）：Profile/Appearance/Notifications | Connection/Channels/Communications/Talk/Devices/Cloud Workers | Agents/Labs/Models/MCP/Memory/Automation | Security/Secrets/Approvals | Infrastructure/Advanced/Debug/Logs/About
- 全局通知、连接丢失重连提示、版本更新页

**聊天页（核心界面）**：
- **transcript 布局**：居中可读框架对齐 composer；assistant/工具输出左对齐、自己的消息右对齐；多用户时他人消息左对齐带头像+名字+稳定身份色，assistant 回复带 "Replying to name" 标记；本地斜杠命令输出渲染为居中通知行；连续重复纯文本消息折叠为单气泡+计数徽章
- **消息操作**：用户消息 hover 显示 rewind 按钮（确认弹窗+"不再询问"，`sessions.rewind`）+ 右键 Rewind to here / Fork from here；transcript 分支菜单（分支最新消息/条数/新旧，`sessions.branches.list/switch`）
- **工具活动卡片**：类型感知行——shell 命令语法高亮+终端式输出、edit/write 内联 diff+行号+`+added -removed` 统计、连续同类调用折叠摘要（"Ran 13 commands, read 6 files, edited 9 files"）、运行中最新调用名作为组头、展开查看剩余参数与原始输出；可选 AI 用途标题（`chat.toolTitles`，utility model 路由+服务端缓存）
- **统一侧面板**（每 Chat 面板一个，状态按会话持久化，可拖拽排序标签、右/下停靠、扩容覆盖）：Terminal（Ctrl+` 切换，多 shell 标签，Codex/Claude 会话自动选中展开）、Browser（远程浏览器快照+URL 栏+前进后退+点击/滚轮/输入转发；Annotate 手绘标注与 Inspect 元素检查生成结构化标注卡入 composer，上限 4 卡 8000 字符）、Files（线程文件/项目文件/工件，⇧⌘B，变更文件计数徽章）、Tasks（后台任务 rail）、Review、Side chat（companion 侧问）、Desktop、Discussion
- **session rail**（右侧 400px 或 overlay）：紧凑 pill 显示实时 digest；展开显示评估/计划进度/PR/耗时/只读 companion 线程；卡住或需输入时展开一次；结束/失败冻结"finished"时间；`/btw` `/side` 打开
- **PR/分支 chips**（composer 上方）：会话 checkout 在非默认分支时显示 PR 号/repo/分支/diff 计数/CI pill/状态；无 PR 时显示分支行+diff 大小+Create PR 按钮；CI 监控 popover（passed/failed/running/skipped+链接）；GitHub 限流时保留最后状态+过期警告
- **会话 diff 面板**：分支按钮打开密集逐文件 viewer（规范化增删改计数、可折叠文件、wrap/统一/分栏布局、复制/打开操作、"N unmodified lines" 标记；footer 切换全部变更/未提交/单个 commit+领先 merge base 信息；`sessions.diff` 服务端计算）
- **composer**：`+` 能力菜单——Skills 会话级开关 / Connectors（MCP 服务器会话级启用，session 标签，Add MCP server 选作用域本会话/全局，Tool access 列工具）/ Web search 开关 / Manage plugins；"N session overrides" pill 一键清除；模型/思考级别覆盖（chat header 稀疏覆盖）；附件（图片+文件，浏览器本地暂存跨路由/重载/重启恢复，上限 25MiB/草稿）
- **chat header**：标题点击重命名、workspace chip（复制 checkout 路径/分支，本地可 reveal 文件管理器）、facepile（≤4 观众头像+溢出计数）、owner chip+参与者 facepile、Runs on Cloud chip（Move session…/Stop cloud worker…）、归档横幅+Unarchive
- 发送语义：chat.send 非阻塞立即 ack {runId,status:started}，流经 chat 事件；同 idempotencyKey 重发返回 in_flight/ok；乐观消息在历史快照落后时保持；工具终态事件后重载历史+合并小乐观尾；**Show more/less**（chat.message.get 拉全文）；assistant 生成图片走 artifacts.download 短期 URL+缩略图+Open/Download/Copy；显示规范化（剥离指令标签/工具调用 XML/控制 token/NO_REPLY/HEARTBEAT_OK）
- 命令面板 ⌘K：跨 agent 有界线程搜索+导航命令

**Agents 页**（/settings/agents，7 面板）：overview（身份 name/emoji/头像编辑、模型选择+fallback 解析、runtime 标签、config 表单重载/保存）、files（bootstrap 文件浏览/编辑，AGENTS/SOUL/IDENTITY/USER/MEMORY）、tools（工具策略）、skills（技能开关/allowlist）、channels（绑定频道）、cron（该 agent 自动化）、memory（dreaming 状态/开关/Dream Diary 阅读；memory-wiki 插件时加 Imported Insights 与 Memory Wiki 子标签：聚类综合/实体/概念页+注解源+报告）

**Sessions 页**（/sessions + worktrees 标签）：概览瓷砖（会话数/活跃运行/未读/总 token/归档数）；表格行（kind 图标+运行点、状态点+标签、Tokens 列上下文窗口用量条、行抽屉 agent runtime+运行时长）；筛选排序分组；原生 Claude/Codex sidebar 目录流式对接（30s 节流+变化触发快刷）；Fork 按钮分支 transcript

**New Session 页**（/new 全页草稿，首条消息才创建）：Place 选择器（Gateway 本地/云 profile/已注册项目/GitHub URL 克隆/Git checkout 时 Worktree 隔离+基分支选择器+命名）、模型/推理级别 footer、Incognito 开关（内存态会话，重启即失）、偏好持久化（durable profile 存每 agent 最新 folder/worktree/model/thinking + 最近项目）

**Workboard 看板页**（/workboard，插件）：状态列 triage/backlog/todo/scheduled/ready/running/review/blocked/done；视图预设过滤（隐藏空列开关）；agent 过滤器；卡片（优先级/标签/指派 agent/链接 task-run-session/执行元数据/尝试与评论与工件附件/事件历史/归档）；卡片详情 modal、卡片操作、状态持久化

**Activity 页**（Settings›System，双标签+深链 inspector）：Sessions（按日分组近期会话活动，搜索/时间/人员过滤，活跃行 Inspect run）；Live activity（浏览器本地临时工具活动观察者，脱敏摘要+截断输出预览，参数只记字段数）；Run inspector（深链 /activity?view=run&run=，读 audit.run.inspect 不可变投影：信任域/入口/调用者/代理主体/发起人/agent 定义/运行时实例/授权/证据/血缘/决策回执，证据状态 Absent/unattributed/unknown/unsupported 四态）

**Tasks 页**（/tasks）：活跃+近期后台任务台账（tasks.list），链接会话，取消（tasks.cancel）；聊天内 Background tasks rail 选择行打开任务实时状态+transcript/prompt/output inspector 详情侧栏

**Automations 页**（/automations）：统计卡（自动化数/失败数/调度器状态/下次唤醒）；Automations 标签（All/Active/Paused 过滤、搜索、调度与上次运行过滤、行操作菜单、入门建议）；Run history 标签（跨任务近期运行）

**Usage 页**（/usage）：用量成本（usage.status/cost/sessions.usage），按 agent 聚合或 agentScope all、时区感知日历桶、时序图、日志条目

**Plugins 页**（/settings/plugins，Plugins hub：installed/discover 标签）：已装清单+策展商店+ClawHub 搜索、安装/卸载/启用禁用（需重启提示）、MCP 服务器行编辑 mcp.servers

**Skills 页**（/skills + /skills/workshop）：技能状态/开关/安装/API key 更新；Skill Workshop（提案/评审/回滚/事件历史）

**Devices 页**（/settings/devices）：配对设备记录+节点目录+实时 presence 合一清单（host 固定首位）；配对客户端显示连接状态/角色/令牌/能力/命令；重复配对折叠组+批量清理陈旧项；设备配对与节点批准内联处理；移动 setup code（QR）创建

**Channels/Communications/Talk 页**：频道状态（内置+插件）、QR 登录（web.login.*）、每频道配置；Talk 语音目录/配置（talk.catalog）、实时语音会话（WebRTC/中继）、TTS 状态/提供方/开关/一次性转换

**Cloud Workers 页**：环境清单与状态（lifecycle：requested->provisioning->bootstrapping->ready->attached->idle->draining->destroying/destroyed/failed/orphaned）、创建/销毁、桌面观察（worker.desktop.observe）

**Model Providers/Setup 页**：35+ 提供方配置、OAuth 订阅认证、自定义/自托管端点、模型目录

**Security/Secrets/Approvals 页**：安全策展行（gateway auth/exec 策略/浏览器/工具 profile/设备认证/移动配对）；Secrets 团队作用域条目管理（env 值可见/secret 永不回显、Bulk Add 多行 dotenv）；审批中心（exec/plugin/system-agent 最新优先 30 天历史、kind 过滤、游标分页、决策/原因/来源会话/决策人归因）

**Dashboards 页**（/dashboards）：可见 dashboard 会话列表（visible spawn 的持久面板会话）；dashboard 会话 = agent 可通过 sessions_spawn visible:true 创建的持久工作面板（board-session-surface 承载）

**Profile 页**：durable 身份（显示名/头像/关联邮箱/可选 GitHub 身份；GitHub 关联启用公开 commit 共同作者署名）

**Custodian/Portals/Apps/Lobsterdex/Labs/Debug/Logs/About**：系统看护、门户、应用扩展、实验特性、调试工具（诊断/连接状态）、日志尾随（logs.tail 游标/字节上限）、关于/更新

**多端**：macOS 菜单栏应用（标题栏 mark）、iOS/Android Nodes（配对/Canvas/相机/屏幕录制/位置/语音）、WebChat、Windows Hub；Telegram Mini App（/dashboard 打开）

## 三、提示词组装机制

### 1. 三层组装
- `buildAgentSystemPrompt`：纯渲染器（不读全局配置）
- `resolveAgentSystemPromptConfig`：解析配置项（owner 显示/TTS 提示/模型别名/记忆引用模式/子代理委派模式）
- 运行时适配器：收集实时事实（工具、沙箱状态、频道能力、上下文文件、provider 贡献）调用门面

### 2. 固定段落结构（分 cache 边界）
**稳定前缀（cache 边界之上）**：Tooling（结构化工具事实+progress_card 指南）/ Execution Bias（当轮内行动直到完成或阻塞）/ Promised Work（承诺工作必须闭环）/ Safety / Skills / OpenClaw Control / Self-Update / Workspace / Documentation / Workspace Files / **Project Context**（bootstrap 文件注入）

**动态后缀（cache 边界之下）**：Temporal Context（日期时区）/ Assistant Output Directives / Collapsible Details / Messaging / Voice / Group Chat Context / Reactions / Heartbeats / Runtime（host/OS/node/model/repo/thinking level）/ Reasoning

### 3. Provider 贡献机制
Provider 插件可：替换 3 个命名核心段（interaction_style/tool_call_style/execution_bias）、注入稳定前缀、注入动态后缀（GPT-5 家族 friendly 风格等）

### 4. Prompt 模式
- full（默认全段）/ minimal（子代理：去掉 Memory Recall、Self-Update、Model Aliases、User Identity、输出指令、Messaging、Heartbeats 等；注入标记为 Subagent Context）/ none（仅身份行）
- 委派模式：prefer（主会话，加 Delegation 段：保持响应、隐藏子代理做内部杂务、可见侧边栏会话做用户关注的工作）/ suggest（默认其他）；ultra thinking 级别加 Proactive Sub-Agent Orchestration 段（并行调查/实现/验证）

### 5. Bootstrap 注入
AGENTS.md/SOUL.md/IDENTITY.md/USER.md/BOOTSTRAP.md（仅全新 workspace）/MEMORY.md（存在时）；首轮注入 system prompt 的 Project Context；空文件跳过；超限截断并注入提示；子代理仅注入 AGENTS.md；`agent:bootstrap` 钩子可改写

### 6. 长任务指导（Tooling 段）
未来工作用 cron（不要 exec sleep 轮询/yieldMs 技巧）；后台命令用 exec/process 一次启动靠推送唤醒；大任务优先 sessions_spawn（完成自动 announce 回请求者，不要轮询 subagents list）

## 四、核心机制

### 1. Agent Loop
```
agent RPC -> 校验参数 -> 解析 session -> 持久化元数据 -> 立即返回 {runId, acceptedAt}
agentCommand：解析模型/thinking 默认值 -> 载入技能快照 -> runEmbeddedAgent
runEmbeddedAgent：
  1. lane 队列串行化（session lane + global lane 并发帽）
  2. 解析模型 + auth profile
  3. 构建 OpenClaw session（bootstrap/context 注入）
  4. 订阅运行时事件 -> agent 流（tool/assistant/lifecycle）
  5. writer claim 围栏（activeWriterRunId）
  6. 运行超时 abort
  7. 返回 payload + usage
agent.wait：等待 lifecycle end/error -> {status, startedAt, endedAt, error?}
```

### 2. 会话与频道模型
- **session = 本地模型上下文；conversation = 外部精确地址**（peer/channel/thread）；两者关联但不等同
- 会话种类：main/group/cron/hook/node/other；`session_key_contract` 表固化契约
- 消息生命周期钩子：message_received/message_sending/message_sent；静默 token NO_REPLY 过滤

### 3. Context Engine（可插拔上下文引擎）
四个生命周期点：**Ingest**（新消息入库时可自建索引）→ **Assemble**（每次运行前返回预算内消息集 + systemPromptAddition）→ **Compact**（窗口满时摘要）→ **After turn**（持久化/后台压缩/更新索引）；可选 maintain()（transcript 安全重写）+ 子代理生命周期钩子（prepareSubagentSpawn/onSubagentEnded）；内置 legacy 引擎（no-op ingest/透传 assemble/内置摘要 compact）

### 4. 工具系统
- 内核工具：read/bash(grep/find/ls)/edit/write/apply_patch(OpenAI 系默认)/process/exec；会话编排工具（sessions 族/conversations 族/subagents/session_status）；媒体工具（image/music/video/pdf/tts）；web 工具（web_search/web_fetch，SSRF 防护）；computer 工具（浏览器/屏幕）；cron 工具；goal/progress_card/ask_user 工具
- **工具策略**：tool profile（coding/messaging）+ allow/deny + 分组/provider/每 agent 策略 + 沙箱策略
- 工具结果 middleware（tool_result_persist 同步转换落盘前）；before_tool_call/after_tool_call 钩子（block 终止语义）

### 5. 事件流（WS event families）
chat（chat.inject 等，delta 带 deltaText，message 为累计快照）/ session.message / session.operation / session.tool / session.approval（净化审批真相）/ session.observer（安全头条+状态 digest）/ sessions.changed（索引变化，activeRunIds 聚合语义）/ presence / tick / health / heartbeat / cron / shutdown / node.pair.requested|resolved / node.invoke.request / device.pair.requested|resolved / device.pair.setup.completed / voicewake.changed / config.changed（只带哈希不带内容）/ skills.changed / exec.approval.requested|resolved / plugin.approval.requested|resolved

### 6. RPC 方法族（WS，帧协议 req/res/event）
| 族 | 方法 |
|---|---|
| 系统 | health, status, diagnostics.stability, system-presence, system-event, last-heartbeat, set-heartbeats, gateway.suspend.prepare/status/resume, gateway.restart.request |
| 模型用量 | models.list, usage.status, usage.cost, sessions.usage(.timeseries/.logs), doctor.memory.* |
| 频道 | channels.status, channels.logout, web.login.* |
| 消息 | send, logs.tail, chat.history, chat.send, chat.abort, chat.inject, chat.message.get, chat.toolTitles |
| 会话 | sessions.list/subscribe/messages.subscribe/describe/resolve/create/dispatch/reclaim/move/patch/patchMany/assignOwner/reset/delete/compact/get/groups.*/preview/cleanup/search/abort/steer/send |
| Agent | agent, agent.wait, agents.list/create/update/delete, agents.files.*, agents.workspace.*, agent.identity.get |
| 终端 | terminal.open/input/resize/close/list/attach/upload |
| 语音 | talk.catalog/config/session.*/client.*/mode/event/speak, tts.* |
| 自动化 | wake, cron.get/list/status/add/update/remove/run/runs |
| 任务 | tasks.list/get/cancel |
| 审批 | approval.history/get/resolve, exec.approval.*, plugin.approval.* |
| 设备 | device.pair.*, device.token.rotate/revoke, node.pair.*, node.list/describe/rename/invoke/invoke.result, node.pending.pull/ack/enqueue/drain, node.event, node.pluginTools.update, mcp.tools.call.v1 |
| 插件 | plugins.list/search/install/setEnabled/uninstall |
| 密钥配置 | secrets.reload/resolve/store.*, config.get/set/patch/apply/schema/schema.lookup, update.run/status, wizard.* |
| 环境 | environments.list/status/create/destroy, worker.desktop.observe |
| 工件 | artifacts.list/get/download, audit.activity.list/run.inspect/list |
| UI | ui.command（面板拆分/导航命令）, portals.* |
| 会话补充 | sessions.rewind/fork, sessions.branches.list/switch, sessions.diff, sessions.messages.subscribe/unsubscribe, chat.startup, controlUi.sessionPullRequests.changed |

**帧协议细节**：首帧必须 connect（挑战 nonce+ts，设备签名 v3 绑定 platform/deviceFamily）；req `{type:"req",id,method,params,traceparent?}` / res `{type:"res",id,ok,payload|error{code,message,details,retryable,retryAfterMs}}` / event `{type:"event",event,payload,seq?,stateVersion?}`；副作用方法强制 idempotency key（短期去重缓存）；MISSING_SCOPE 结构化权限错误；hello-ok.features.methods 为保守发现列表（非全量）；预连接帧上限 64KiB

## 五、存储设计

### 1. 数据库布局
- **共享状态库** `~/.openclaw/state/openclaw.sqlite`：跨 agent 状态（schema_meta, state_leases, cron_*, operator_approvals, device/node pairing, secret_store_entries, projects, agent_databases 注册, worker_*, audit_*, quarantined_databases, user_profiles, user_preferences, model_catalog_remote...）
- **每 agent 库** `~/.openclaw/agents/<agentId>/agent/openclaw-agent.sqlite`：

| 表 | 说明 |
|---|---|
| sessions | session_id PK, session_key, session_scope, created/updated_at, transcript_updated/observed_at, provenance, acp_owned, plugin_owner_id, started/ended_at, status(running/done/failed/killed/timeout), chat_type, channel, account_id, primary_conversation_id FK, model_provider, model, agent_harness_id, parent_session_key, spawned_by, display_name |
| session_routes | session_key PK -> session_id（键到代际路由） |
| conversations | conversation_id PK, channel, account_id, kind(direct/group/channel), peer_id, delivery_target, parent_conversation_id, thread_id, native ids, label, metadata_json |
| session_conversations | 会话-会话地址关联 |
| transcript_events | session_id, seq, event_json, created_at（事件溯源 transcript，FTS5 索引 session_transcript_fts） |
| transcript_event_identities | 事件身份 |
| transcript_rewrite_watermarks | 代际重写水位（generation token） |
| session_transcript_index_state / active_events / archives | 投影/活跃集/归档 |
| session_key_contract | 键契约 |
| session_windows | 会话窗口 |
| session_members / session_participants | 共享成员/参与者历史 |
| session_progress_cards | 进度卡 |
| session_suggestions | 任务建议 |
| session_nodes | 会话绑定 exec node |
| standing_intents(+Fts) | 常设意图 |
| memory_index_chunks/sources/meta/chunk_provenance/recall_metadata, memory_embedding_cache | 记忆索引（FTS+向量+溯源） |
| board_tabs / board_widgets | 看板 |
| heartbeat_outcomes | 心跳结果 |
| message_tool_run_outcomes | 消息工具运行结果 |
| cache_entries | 缓存 |
| context_engine_turn_outbox | 上下文引擎轮出箱 |
| acp_parent_stream_events | ACP 父流 |
| trajectory_runtime_events | 轨迹事件 |
| auth_profile_store | 每 agent 认证 |
| schema_meta | schema 版本 |

### 2. 文件系统设计
```
~/.openclaw/                          # 状态目录（OPENCLAW_STATE_DIR）
├── openclaw.json                     # 主配置
├── state/openclaw.sqlite             # 共享状态库
├── workspace/                        # 默认 agent workspace（其他: workspace-<id>）
│   ├── AGENTS.md / SOUL.md / IDENTITY.md / USER.md / MEMORY.md / BOOTSTRAP.md
│   ├── memory/YYYY-MM-DD.md          # 每日记忆（FTS 索引，不自动注入）
│   ├── DREAMS.md                     # dreaming 报告
│   └── skills/                       # workspace 级技能
├── agents/<agentId>/agent/           # 每 agent 状态目录
│   ├── openclaw-agent.sqlite         # 每 agent 会话库
│   └── auth profiles
├── skills/                           # 托管技能根
├── worktrees/<repo指纹>/<名称>        # 托管 git worktree（分支 openclaw/<name>）
├── plugins/                          # 安装的插件
└── logs/
```

## 六、对 AI 工作台的借鉴要点

1. **Gateway 单控制面模式**：一个常驻守护进程统一 WS 协议（req/res/event 帧）服务所有客户端（Web UI/CLI/移动节点），比每客户端独立连后端更利于多端同步
2. **角色=完整隔离域**（workspace 文件+状态库+auth+模型注册表），binding 把入口路由到角色 -- 直接映射"流程工作人员"角色设计
3. **lane 队列 + writer claim 围栏**：会话级串行+全局并发帽+代际写围栏，多端写入一致性核心
4. **队列模式 steer/followup/collect/interrupt**：运行中插话的产品化答案
5. **会话三层层主归属**（Creator/Owner/Participants）：多人协作工作台的会话归属模型
6. **子代理 yield+announce**：不轮询、完成推送，与 opencode 后台子代理同思路但更完整（含线程路由保留）
7. **五层记忆信任模型 + provenance 列**：记忆安全边界在写入路径，晋升过确定性闸门
8. **提示词 cache 边界**：稳定段上/易变段下，前缀缓存复用 -- 多轮成本优化关键
9. **会话=模型上下文 vs conversation=外部地址**分离：支持一上下文多投递
10. **工具卡片类型感知渲染 + 折叠摘要**：Web 端工具活动展示蓝本
11. **状态感知 stateVersion/changesSince + watcher**：长任务协作通知模型
12. **Control UI 侧边栏分区**（Pages/Other/Groups/Coding/自定义组）+ 会话行状态徽章体系：多角色工作台信息架构参考
