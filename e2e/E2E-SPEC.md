# 龙虾 Lobster 前端 E2E — 业务场景与操作步骤（v2·复杂作业流）

> 本文件先明确**系统定位**，再给出两条**复杂用户作业场景**（开发者 / 运维），
> 每条场景把工作台的多块能力串成一条真实工作流；最后给出**精确到页面/元素/输入/预期结果**
> 的操作步骤，作为后续测试用例与 Playwright 代码的唯一依据。

## 0. 系统定位（先想清楚再设计）

龙虾是**本地优先的企业级多角色 AI 工作台**：一个 Spring Boot 单进程（:18790）同时承载
「开发者编程能力（对齐 OpenCode）」与「流程人员作业能力（对齐 OpenClaw）」，共享同一 Gateway
内核、同一套 WebSocket 帧协议与 SQLite 数据底座。两类典型用户：

- **开发者**：用对话完成编码任务，需要「可复用的上下文（参考库）+ 自动化（钩子）+ 能力扩展（技能/插件）」，
  交付产物后可把会话分享给评审者。
- **流程/运维人员**：把工作台接入企业系统（集成）、装上扩展（插件市场）、把运行结果自动推送出去（钩子），
  对高风险执行做**审批治理**，并保证一切操作进入**审计台账**留痕。

前端是 vanilla-JS 单页应用（index.html / app.js），使用 Mock LLM 时助手回复为固定文案
「（Mock 模式）已收到消息…」，但**控制面（control plane）全部真实可用**：会话、参考库、集成、
钩子、技能、插件市场、审批、审计、配置、产物、分享、命令面板。因此"串全功能"就是指把上述控制面
按真实作业顺序联动起来，而不是孤立地打开每个页签。

> 说明：看板（workboard）、定时任务（cron）、任务台账（tasks）、用量（usage）、记忆（memory）、
> 设备（device）、频道（channel）等模块在**前端 UI 暂未暴露**，但其 RPC 已由 `WsRpcE2ETest`
> （23 个用例）在 WebSocket 层完整覆盖。本前端场景聚焦 UI 实际控制面。

---

## 1. 场景 A — 开发者「可复用团队编码环境」搭建 + 一次任务交付

**人物**：开发者 Alice。**目标**：给团队搭一个带「代码规范上下文 + 运行结束自动 lint 钩子 +
启用的技能」的编码工作台，随后真正完成一次登录模块编码任务，把结果以只读链接分享给 reviewer，
并在审计中确认本次活动链路可追溯。

**前置**：`target/test-state-pw` 为空 → 无用户 → 鉴权不启用 → 前端免登录。

| # | 页面区域 | 操作元素(选择器) | 输入 / 动作 | 预期结果 |
|---|---|---|---|---|
| A1 | 顶栏状态 | `#conn` / `#connText` | 打开 `/` 并等待 WS | `#conn` 类含 `on`；`#connText` 文本「已连接」 |
| A2 | 左栏会话 | `#newSession` + `prompt` | 点击「+ 新会话」；key=`feat-login` | `#sessions .session` 出现文本 `feat-login`（已成为当前会话） |
| A3 | 参考库 | `.tab[data-tab="refs"]` → `[data-act="ref-add"]` + 两个 `prompt` | 名称=`acme-style-guide`；URI=`https://github.com/acme/style-guide` | `reference.install` 调用；`#refList .item` +1；`reference.changed` 事件驱动重渲 |
| A4 | 钩子 | `.tab[data-tab="hooks"]` → `[data-act="hook-add"]` + 两个 `prompt` | 事件=`agent.run.ended`；命令=`echo lint-ok` | `hooks.install` 调用；`#hookList .item` +1 |
| A5 | 技能 | `.tab[data-tab="skills"]` → `#skillList .item` 的启用按钮 | 点击某技能项的启用 | `skills.setEnabled` 调用；该项重渲为 enabled 态（断言 item 含 `enabled` 类或文本） |
| A6 | 设置 | `.tab[data-tab="settings"]` → `#cfgList` / `#pluginList` | 渲染配置项；修改某配置 `input` 并失焦 | `config.list` 渲染项；`config.set` 被调用；`#pluginList .item` 渲染已装插件 |
| A7 | 中部聊天 | `#input` → `#send` | 输入「实现用户登录接口并写单元测试」并发送 | `#messages .msg.user` 含该文本；随后 `#messages .msg.assistant` 出现且含「Mock 模式」；`#send` 恢复可用 |
| A8 | 产物 | `.tab[data-tab="arts"]` → `#artList` | 点击「产物」页签 | `artifact.list` 渲染；`#artList` 可见且无 JS 异常 |
| A9 | 分享 | `#input` 输入 `/share` 回车 | 在输入框输入 `/share` 回车 | `share.create` 调用；`#shareModal` 可见；`#shareUrl` 文本含 `/share/`；点 `#shareClose` 后隐藏 |
| A10 | 审计 | `.tab[data-tab="audit"]` → `#auditList` | 点击「审计」页签 | `audit.activity.list` 渲染；列表含本次 参考库/钩子/会话/分享 等相关活动事件（metadata-only） |

**串联价值**：A2–A6 完成"环境搭建"（上下文+自动化+能力），A7–A8 完成"任务交付"，A9–A10 完成
"协作与合规"。一条流覆盖 会话 / 参考库 / 钩子 / 技能 / 配置 / 聊天 / 产物 / 分享 / 审计 共 9 块。

---

## 2. 场景 B — 运维「企业集成 + 扩展 + 治理」闭环

**人物**：运维 Bob。**目标**：把龙虾接入企业系统（集成）、从市场装上扩展插件、装一个把运行结果
推送出去的钩子，对一次需要审批的高风险执行做治理（通过/拒绝），并最后在审计台账确认上述操作全部留痕。

**前置**：同 A（免登录）。

| # | 页面区域 | 操作元素(选择器) | 输入 / 动作 | 预期结果 |
|---|---|---|---|---|
| B1 | 顶栏状态 | `#conn` / `#connText` | 打开 `/` 并等待 WS | `#conn` 类含 `on`；`#connText` 文本「已连接」 |
| B2 | 集成 | `.tab[data-tab="ints"]` → `[data-act="int-add"]` + 三个 `prompt` | 名称=`jira`；类型=`key`；Key=`jira-xxxx` | `integration.install` 调用；`#intList .item` +1 |
| B3 | 插件市场 | `.tab[data-tab="market"]` → `[data-act="market-refresh"]` → `#marketList .item` 安装按钮 | 点击刷新；点击某插件「安装」 | `plugins.marketplace` 渲染目录（≥1 项）；`plugins.install` 调用；切「设置」页签后该项出现在 `#pluginList` 且为 enabled |
| B4 | 钩子 | `.tab[data-tab="hooks"]` → `[data-act="hook-add"]` + 两个 `prompt` | 事件=`agent.run.ended`；命令=`curl -X POST https://hooks.acme/jira` | `hooks.install` 调用；`#hookList .item` +1 |
| B5 | 审批中心 | `.tab[data-tab="approvals"]` → `#approvalList` | 点击「审批」页签；若存在待审项则点「通过/拒绝」 | `approval.list` 渲染；面板可见；若有 pending 项，`approval.resolve` 被调用并就地重渲（治理闭环可操作） |
| B6 | 审计 | `.tab[data-tab="audit"]` → `#auditList` | 点击「审计」页签 | `audit.activity.list` 渲染；列表含 集成/插件/钩子 等活动事件 |
| B7 | 新建会话跑巡检 | `#newSession` + `prompt` + `#input`/`#send` | key=`ops-health`；发送「巡检服务健康度并告警」 | `#sessions .session` 出现 `ops-health`；`#messages .msg.assistant` 出现且含「Mock 模式」 |
| B8 | 分享（可选） | `#input` 输入 `/share` 回车 | 在输入框输入 `/share` 回车 | `#shareModal` 可见；`#shareUrl` 含 `/share/` |

**串联价值**：B2–B4 完成"接入与自动化"（集成+插件+钩子），B5 完成"治理"，B6 完成"合规留痕"，
B7–B8 完成"实际作业与协作"。一条流覆盖 集成 / 插件市场 / 钩子 / 审批 / 审计 / 会话 / 聊天 / 分享 共 8 块。

---

## 3. 覆盖度矩阵（前端 UI 场景 vs WS 层 E2E）

| 能力 | 前端场景覆盖 | WS 层 `WsRpcE2ETest` 覆盖 |
|---|---|---|
| 会话 / 聊天 / 分享 / 命令面板 | ✅ A2,A7,A9 / B7,B8 | ✅ sessions.*, chat.*, share.*, command.* |
| 参考库 | ✅ A3 | ✅ reference.* |
| 集成 | ✅ B2 | ✅ integration.* |
| 钩子 | ✅ A4,B4 | ✅ hooks.* |
| 技能 | ✅ A5 | ✅ skills.* |
| 插件 / 市场 | ✅ A6,B3 | ✅ plugins.* |
| 审批 | ✅ B5 | ✅ approval.*, exec.approvals.* |
| 审计 | ✅ A10,B6 | ✅ audit.* |
| 配置 | ✅ A6 | ✅ config.* |
| 产物 | ✅ A8 | ✅ artifact.* |
| 看板 / cron / tasks / usage / memory / device / channel / auth / permission | —（UI 未暴露） | ✅ 全部在 WsRpcE2ETest |

---

## 4. 操作步骤 → 测试用例映射

- **编程场景** `programming.spec.js`：P1=A1；P2=A7；P3=命令面板（⌘K `/share`/`/new` 过滤）；
  P4=A5（技能）；P5=A6（设置/插件）；P6=A9（`/share`）。新增：P7=A2（新会话）、P8=A3（参考库）、
  P9=A4（钩子）、P10=A8（产物）、P11=A10（审计）。
- **流程场景** `flow.spec.js`：F1=B2（集成）；F2=B3（市场刷新+安装）；F3=B4（钩子）；F4=B5（审批）；
  F5=B6（审计）；F6=B1（连接）；F7=B7（新会话）；F8=B8（分享）；F9=端到端串联（B2→B4→B5→B6→B7→B8）。
  新增参考库步骤并入串联。

> 下一步：按以上步骤改写 `programming.spec.js` 与 `flow.spec.js` 的断言（精确到元素与文本），
> 再 `npx playwright test` 跑到全绿。
