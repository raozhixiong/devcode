# Lobster 龙虾工作台 · 复杂用户作业场景与操作步骤（设计稿 v2）

> 先厘清**系统定位**，再设计**能串起全功能**的复杂用户作业场景，最后落到**精确到页面/元素/输入/预期结果**的操作步骤。
> 所有步骤对照 `requirement/03-龙虾-全量功能需求.md` 的模块编号（A~I），并标注真实 RPC 与前端选择器。
> 前端当前以 Mock LLM 运行，助手回复为固定文案「（Mock 模式）已收到消息…」；控制面（参考库/集成/钩子/技能/插件/审批/审计/配置/产物/分享/会话/命令面板）全部真实可用。

---

## 一、系统定位（先想清楚，再设计）

龙虾是**本地优先、企业内网可用**的多角色 AI 工作台，单一 Gateway 内核 + 统一 SQLite 数据底座。它把两条世界缝合在一起：

1. **开发者世界（对齐 OpenCode）**：会话式编程、工具调用、子代理、Plan 模式、三层权限、writer-claim 围栏。
2. **流程世界（对齐 OpenClaw）**：多角色工作台（PM/tester/ops/reviewer/approver/knowledge/admin）、任务台账、Workboard 看板、Cron 自动化、五层记忆、技能工坊、审批中心、频道接入、审计台账、插件市场、钩子框架、参考库、集成框架、会话分享。

**贯穿所有角色的内核能力**：事件总线（durable + live）、WebSocket 帧协议（connect→req/res/event）、队列模式（steer/followup/collect/interrupt）、会话归属（Creator/Owner/Participants）、状态感知（stateVersion/changesSince）。

因此，"把全功能串起来"的真实作业，必然是**跨模块、跨角色、事件驱动**的：一个人配置上下文与自动化 → 发起会话跑 Agent → 产生产物与权限/审批事件 → 审计留痕 → 分享给协作者。下面三个场景即按此设计。

---

## 二、复杂用户作业场景

### 场景 A — 开发者交付闭环（Developer Delivery Loop）【编程场景】
**角色**：开发者（developer） + 评审员（reviewer）
**叙事**：开发者接到"实现用户登录并写单测"的需求，先把团队编码规范作为上下文注入、布置会话钩子做自动初始化、启用代码评审技能并装好 Jira 插件，然后发起编码会话，看到流式回复与（真实 LLM 下）工具卡片，产物进入工件面板，最后对需要 exec 审批的动作在审批中心放行，并 `/share` 把会话只读链接发给评审员。
**串起模块**：FR-I3 参考库、FR-I1 钩子、FR-I5/Skills 技能、FR-H-4/FR-I5 插件、FR-A4 工具、FR-I6 工件、FR-A5-5/FR-G 审批、FR-I7 分享、FR-E 开发者工作台。
**可断言链路**：connect → /new 建会话 → 挂载参考库 → 装钩子 → 启技能/插件 → 发编码任务看流式回复 → 产物面板 → 审批中心 → /share。

### 场景 B — 运维自动化治理（Ops Automation & Governance）【流程场景】
**角色**：运维（ops） + 审批员（approver） + 知识管理员（knowledge）
**叙事**：运维要把"Agent 跑完自动通报到企业 IM、关键 exec 动作必须审批、所有操作可审计"这套治理落到位。步骤：接入企业 IM 集成（key）→ 装 `agent.run.ended` 钩子把结果推到集成 → 从插件市场装 summarizer 并启用 → 在配置中心设审批策略 → 制造一次 exec 审批请求并在审批中心通过/拒绝 → 打开审计台账确认以上动作全部留痕 → /new 建会话跑"每天 9 点巡检服务状态" → /share 把巡检会话发给同事。
**串起模块**：FR-B3/I4 集成、FR-I1 钩子、FR-I5/FR-H-4 插件市场、FR-H-3 配置中心、FR-A5-5/FR-G 审批、FR-G-5 审计、FR-I7 分享。
**可断言链路**：connect → 接集成 → 装钩子 → 市场刷新+装插件 → 配置 → 审批通过/拒绝 → 审计可见 → /new → /share。

### 场景 C — 跨角色项目协作（Cross-role Project Collaboration）【全平台设计级】
**角色**：PM + 开发者 + 测试 + 审批员
**叙事**：PM 在 Workboard 建卡、指派开发者；开发者 fork 会话做实现；测试用 Memory 检索历史缺陷写回归用例；定时 Cron 每晚汇总；审批员在审批中心处理 exec 放行；admin 在审计台账与设备页做安全治理；最终 `/share` 出项目战报。
**串起模块**：FR-C2 Workboard、FR-C1 任务台账、FR-B1 会话 fork/rewind/归属、FR-D 五层记忆、FR-C3 Cron、FR-A5-5 审批、FR-G 审计/设备、FR-I7 分享、FR-F 流程工作台。
**说明**：本场景含后端专属模块（Workboard/Cron/Memory/Usage/Channels/Devices），当前 SPA 无对应页签，故步骤以"设计级"给出（标注真实 RPC），作为后续前端补齐后的测试 backlog；本稿的 Playwright 实现只覆盖 A、B 的可断言部分。

---

## 三、详细操作步骤（页面 / 输入 / 预期结果）

> 约定：选择器来自 `index.html`/`app.js`；`prompt()` 对话框由测试用 `dialogFiller` 自动填充。
> "可断言"= 当前 SPA 真实渲染、可被 Playwright 校验；"设计级"= 后端 RPC 已存在但 SPA 暂无页签，先记录。

### 场景 A 操作步骤
| # | 页面区域 | 操作(选择器) | 输入 | 预期结果 | 模块/可断言 |
|---|---|---|---|---|---|
| A1 | 顶栏状态 | 打开 `/`，等 `#conn` | — | `#conn` 类含 `on`，`#connText`=已连接 | 内核/可断言 |
| A2 | 左栏会话 | `#newSession`（prompt key） | `feat-login` | `#sessions .session` 出现含 `feat-login` | FR-B1/可断言 |
| A3 | 参考库 | `.tab[data-tab="refs"]` → `[data-act="ref-add"]`（两 prompt） | 名称=`团队编码规范`，URI=`https://kb.corp/style` | `#refList .item` +1（reference.install） | FR-I3/可断言 |
| A4 | 钩子 | `.tab[data-tab="hooks"]` → `[data-act="hook-add"]`（两 prompt） | 事件=`session.created`，命令=`echo init-env` | `#hookList .item` +1（hooks.install） | FR-I1/可断言 |
| A5 | 技能 | `.tab[data-tab="skills"]` → 点某项启用 | 点 `code-review` 项启用按钮 | 该项状态切为 enabled（skills.setEnabled） | FR-I5/可断言 |
| A6 | 设置 | `.tab[data-tab="settings"]` → 插件开关 | 启用某插件 toggle | 配置落库、列表状态翻转（plugins.setEnabled） | FR-H-4/可断言 |
| A7 | 聊天 | `#input` 输入任务 → `#send` | `实现用户登录并写单测` | `#messages .msg.assistant` 首条可见且含 `Mock 模式`；`#send` 恢复可用 | FR-A4/可断言 |
| A8 | 产物 | `.tab[data-tab="arts"]` | — | `#artList` 可见（artifact.list；真实 LLM 下会有文件工件） | FR-I6/可断言 |
| A9 | 审批 | `.tab[data-tab="approvals"]` | — | 若有 exec 请求，`#approvalList` 出现 pending 项与通过/拒绝按钮 | FR-A5-5/可断言 |
| A10 | 分享 | `#input` 输入 `/share` → 回车 | `/share` | `#shareModal` 可见，`#shareUrl` 含 `/share/`；`#shareClose` 关闭 | FR-I7/可断言 |

### 场景 B 操作步骤
| # | 页面区域 | 操作(选择器) | 输入 | 预期结果 | 模块/可断言 |
|---|---|---|---|---|---|
| B1 | 顶栏状态 | 打开 `/`，等 `#conn` | — | `#conn` 类含 `on` | 内核/可断言 |
| B2 | 集成 | `.tab[data-tab="ints"]` → `[data-act="int-add"]`（三 prompt） | 名称=`team-im`，类型=`key`，Key=`x-corp-123` | `#intList .item` +1（integration.install） | FR-B3/I4/可断言 |
| B3 | 钩子 | `.tab[data-tab="hooks"]` → `[data-act="hook-add"]`（两 prompt） | 事件=`agent.run.ended`，命令=`echo notify-im` | `#hookList .item` +1（hooks.install） | FR-I1/可断言 |
| B4 | 市场 | `.tab[data-tab="market"]` → `[data-act="market-refresh"]` → 装某项 | 点 summarizer 的"安装" | `#marketList .item`≥1；`plugins.list` 出现已装项（plugins.marketplace/install） | FR-I5/可断言 |
| B5 | 设置 | `.tab[data-tab="settings"]` → 配置项 | 改某 config 值 → 失焦 | `config.set` 落库，`config.list` 反映 | FR-H-3/可断言 |
| B6 | 审批 | `.tab[data-tab="approvals"]` → 点通过/拒绝 | 对 pending 项点"通过" | 该项从列表移除/标记 resolved（approval.resolve） | FR-A5-5/可断言 |
| B7 | 审计 | `.tab[data-tab="audit"]` | — | `#auditList` 可见且含 B2~B6 的活动条目（audit.activity.list） | FR-G-5/可断言 |
| B8 | 新建会话 | `#newSession`（prompt key） | `nightly-probe` | `#sessions .session` 出现该 key | FR-B1/可断言 |
| B9 | 聊天 | `#input` 输入任务 → `#send` | `每天 9 点巡检服务状态` | `#messages .msg.assistant` 首条可见（Mock 回显；真实下触发 Cron） | FR-C3/可断言(回显) |
| B10 | 分享 | `#input` 输入 `/share` → 回车 | `/share` | `#shareModal` 可见，`#shareUrl` 含 `/share/` | FR-I7/可断言 |

### 场景 C 操作步骤（设计级，标注真实 RPC，待前端补齐）
| # | 模块 | 操作(intent) | 真实 RPC | 预期 |
|---|---|---|---|---|
| C1 | Workboard | PM 建卡并指派开发者 | `workboard.cards.create` + `workboard.cards.move` | 卡片进入 TODO/RUNNING |
| C2 | 会话 | 开发者 fork 会话做实现 | `sessions.fork` / `sessions.rewind` | 新分支会话出现 |
| C3 | 记忆 | 测试检索历史缺陷 | `memory.search` / `memory.recent` | 返回关联记忆条目 |
| C4 | Cron | 配置每晚汇总 | `cron.add` + `cron.run` | 定时任务登记，运行历史可查 |
| C5 | 审批 | 审批员放行 exec | `approval.request` / `approval.resolve` | 审批中心状态变更 |
| C6 | 审计/设备 | admin 治理 | `audit.activity.list` / `device.pair.*` | 审计台账与设备页留痕 |
| C7 | 分享 | 出项目战报 | `share.create` | 生成只读战报链接 |

---

## 四、与测试用例 / Playwright 的对应关系（下一步）

- **场景 A → `programming.spec.js`**：A1=P1，A2=P2a(新会话)，A3~A6 扩充原 P4/P5 为"配置即作业"步骤，A7=P2，A8=P2b(产物)，A9=P4b(审批)，A10=P6。
- **场景 B → `flow.spec.js`**：B1=F3，B2=F2，B3=F6/F1，B4=F6b，B5=F5b(配置)，B6=F4，B7=F5，B8=F7，B9=F8，B10=F9。
- **场景 C**：登记为后续前端补齐后的测试 backlog（本稿不强行断言未落地页签）。

下一步：将上述"可断言"步骤改写为具体 `test(...)` 用例与 `expect(...)` 断言，运行 `npx playwright test` 至全绿。
