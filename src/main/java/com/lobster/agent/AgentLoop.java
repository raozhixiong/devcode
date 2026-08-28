package com.lobster.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.LlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.store.MessageStore;
import com.lobster.tool.PermissionReply;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心循环（对齐 OpenCode runLoop，M1 版）：
 * 历史 -> LLM 流 -> Part 落库 -> 工具执行 -> 结果作为 user 消息回填 -> continue/stop。
 */
public class AgentLoop {

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final MessageStore store;
    private final EventBus bus;
    private final ToolRegistry tools;
    private final PermissionEngine permissions;
    private final LlmProvider llm;
    private final String agentId;
    private final String model;
    private final PromptAssembler promptAssembler;
    private final java.util.Set<String> busySessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final com.lobster.store.InboxStore inbox;
    private final PlanMode planMode = new PlanMode();
    private final QueueMode queueMode = new QueueMode();
    /** interrupt 请求的中止标志（每 session）。 */
    private final java.util.Set<String> abortRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private com.lobster.store.WriterClaimStore claims;
    private com.lobster.store.MemoryStore memoryStore;
    private com.lobster.store.AuditStore auditStore;
    /** 生命周期钩子引擎（null = 不触发钩子）。 */
    private com.lobster.agent.HookEngine hookEngine;
    /** 角色工具过滤器（null = 不过滤）。 */
    private volatile java.util.function.Predicate<String> toolFilter;
    /** 注入 prompt 的可用技能名（null = 不注入）。 */
    private volatile java.util.List<String> skillNames = java.util.List.of();
    /** 注入 prompt 的可用参考库名。 */
    private volatile java.util.List<String> referenceNames = java.util.List.of();
    /** 当前 run 的 writer claim（run 内有效）。 */
    private final java.util.Map<String, com.lobster.store.WriterClaimStore.Claim> activeClaims =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PlanMode planMode() { return planMode; }

    public QueueMode queueMode() { return queueMode; }

    /** 请求中止某会话的活跃 run（interrupt 队列模式）。 */
    public void requestAbort(String sessionId) {
        abortRequests.add(sessionId);
    }

    private boolean consumeAbort(String sessionId) {
        return abortRequests.remove(sessionId);
    }

    public void setWriterClaimStore(com.lobster.store.WriterClaimStore claims) {
        this.claims = claims;
    }

    public void setMemoryStore(com.lobster.store.MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void setAuditStore(com.lobster.store.AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    public void setHookEngine(com.lobster.agent.HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    public void setToolFilter(java.util.function.Predicate<String> filter) {
        this.toolFilter = filter;
    }

    public void setSkillNames(java.util.List<String> names) {
        this.skillNames = names == null ? java.util.List.of() : names;
    }

    public void setReferenceNames(java.util.List<String> names) {
        this.referenceNames = names == null ? java.util.List.of() : names;
    }

    private boolean toolAllowed(String toolId) {
        java.util.function.Predicate<String> f = toolFilter;
        return f == null || f.test(toolId);
    }

    public AgentLoop(MessageStore store, EventBus bus, ToolRegistry tools,
                     PermissionEngine permissions, LlmProvider llm,
                     String agentId, String model) {
        this(store, bus, tools, permissions, llm, agentId, model, null);
    }

    public AgentLoop(MessageStore store, EventBus bus, ToolRegistry tools,
                     PermissionEngine permissions, LlmProvider llm,
                     String agentId, String model, com.lobster.store.InboxStore inbox) {
        this.store = store;
        this.bus = bus;
        this.tools = tools;
        this.permissions = permissions;
        this.llm = llm;
        this.agentId = agentId;
        this.model = model;
        this.promptAssembler = new PromptAssembler(agentId, model);
        this.inbox = inbox;
        this.contextLimit = 128_000;
        this.contextLimitHolder = 128_000;
        this.summarizer = null;
    }

    public boolean isBusy(String sessionId) {
        return busySessions.contains(sessionId);
    }

    public void run(String sessionId) {
        if (!busySessions.add(sessionId)) {
            return; // 已在运行，输入由 WsHandler 入队
        }
        // writer claim 围栏：会话写入权（被其他 run 持有则拒绝）
        com.lobster.store.WriterClaimStore.Claim claim = null;
        if (claims != null) {
            claim = claims.claim(sessionId, "run_" + sessionId);
            if (claim == null) {
                busySessions.remove(sessionId);
                throw new IllegalStateException("会话 " + sessionId + " 写入权被其他 run 持有");
            }
            activeClaims.put(sessionId, claim);
        }
        publishStatus(sessionId, "busy");
        if (auditStore != null) {
            auditStore.record(agentId, "run.start", sessionId, agentId, "started", null);
        }
        fireHook(Events.AGENT_RUN_STARTED, sessionId, "{\"sessionId\":\"" + sessionId + "\"}");
        try {
            runLoop(sessionId);
            drainInbox(sessionId);
        } finally {
            if (claims != null && claim != null) {
                claims.release(claim);
                activeClaims.remove(sessionId);
            }
            busySessions.remove(sessionId);
            if (auditStore != null) {
                auditStore.record(agentId, "run.end", sessionId, agentId, "completed", null);
            }
            fireHook(Events.AGENT_RUN_ENDED, sessionId, "{\"sessionId\":\"" + sessionId + "\"}");
            bus.publish(new com.lobster.event.LobsterEvent(
                    Events.SESSION_IDLE, sessionId,
                    OM.createObjectNode(), false));
            publishStatus(sessionId, "idle");
            writeEpisodicMemory(sessionId);
        }
    }

    /** 会话结束后写 episodic 记忆（最后一条 assistant 文本）。 */
    private void writeEpisodicMemory(String sessionId) {
        if (memoryStore == null) return;
        try {
            var session = store.findById(sessionId);
            if (session.isEmpty()) return;
            var msgs = store.loadActive(sessionId);
            String summary = msgs.stream()
                    .filter(m -> "assistant".equals(m.role()))
                    .flatMap(m -> m.parts().stream())
                    .filter(p -> p instanceof com.lobster.model.Part.Text)
                    .map(p -> ((com.lobster.model.Part.Text) p).text())
                    .reduce("", (a, b) -> b);
            if (!summary.isEmpty()) {
                memoryStore.writeEpisodic(session.get().sessionKey(), summary);
            }
        } catch (Exception e) {
            // episodic 记忆写入失败不影响会话
        }
    }

    /** 写前校验 claim 仍归本 run（代际不匹配说明被抢占，应中止）。 */
    private boolean checkClaim(String sessionId) {
        if (claims == null) return true;
        com.lobster.store.WriterClaimStore.Claim claim = activeClaims.get(sessionId);
        return claims.validate(claim);
    }

    /** 后台子代理 announce 入口：父会话 busy 时结果入收件箱（轮结束 admit）。 */
    public void enqueueAnnouncement(String sessionId, String text) {
        if (inbox != null) {
            inbox.enqueue(sessionId, text);
        } else {
            store.appendUser(sessionId, List.of(new Part.Text(text, true, false)));
        }
    }

    /** 轮结束：收件箱有内容则合并为新 user 消息并再跑一轮。 */
    private void drainInbox(String sessionId) {
        if (inbox == null) return;
        int guard = 0;
        while (guard++ < 10) {
            List<String> prompts = inbox.drain(sessionId);
            if (prompts.isEmpty()) return;
            String merged = String.join("\n\n", prompts);
            store.appendUser(sessionId, List.of(new Part.Text(merged, false, false)));
            bus.publish(new com.lobster.event.LobsterEvent(Events.PROMPT_ADMITTED, sessionId,
                    OM.createObjectNode().put("text", merged), true));
            runLoop(sessionId);
        }
    }

    private static final int MAX_STEPS = 50;
    private static final int DOOM_LOOP_THRESHOLD = 3;
    /** 溢出阈值：估算 token 超过 contextLimit 的 70% 触发自动压缩。 */
    private static final double COMPACTION_TRIGGER = 0.7;
    /** 自动压缩保留的尾部消息条数（工具结果/最新问答）。 */
    private static final int COMPACTION_KEEP_TAIL = 6;

    private final long contextLimit;
    private volatile long contextLimitHolder;
    private volatile java.util.function.Function<String, String> summarizer;

    public void setSummarizer(java.util.function.Function<String, String> summarizer) {
        this.summarizer = summarizer;
    }

    /** 测试用：调整 contextLimit 触发阈值。 */
    void setContextLimitForTest(long limit) {
        this.contextLimitHolder = limit;
    }

    /**
     * 自动压缩：保留尾部 COMPACTION_KEEP_TAIL 条，其余生成摘要（LLM summarizer 可用时调用，
     * 否则规则摘要：逐条取首行）。返回压缩后的活动历史。
     */
    private List<com.lobster.model.Message> autoCompact(String sessionId,
                                                        List<com.lobster.model.Message> history) {
        if (history.size() <= COMPACTION_KEEP_TAIL) return history; // 太短不压
        bus.publish(new com.lobster.event.LobsterEvent(Events.COMPACTION_STARTED, sessionId,
                OM.createObjectNode().put("messages", history.size()), true));

        List<com.lobster.model.Message> head = history.subList(0, history.size() - COMPACTION_KEEP_TAIL);
        StringBuilder transcript = new StringBuilder();
        for (var m : head) {
            String text = m.parts().stream()
                    .filter(p -> p instanceof Part.Text)
                    .map(p -> ((Part.Text) p).text())
                    .reduce("", (a, b) -> a + b);
            if (!text.isEmpty()) transcript.append(m.role()).append(": ").append(text).append('\n');
        }
        String summary = summarizer != null
                ? summarizer.apply(transcript.toString())
                : "此前会话共 " + head.size() + " 条消息，摘要（规则版）：\n" + transcript;

        String keepFromId = history.get(history.size() - COMPACTION_KEEP_TAIL).id();
        store.compact(sessionId, keepFromId, summary);
        List<com.lobster.model.Message> active = store.loadActive(sessionId);
        bus.publish(new com.lobster.event.LobsterEvent(Events.COMPACTION_ENDED, sessionId,
                OM.createObjectNode()
                        .put("compactedMessages", head.size())
                        .put("activeMessages", active.size())
                        .put("summary", truncate(summary, 2000)), true));
        return active;
    }

    private void runLoop(String sessionId) {
        int step = 0;
        java.util.Map<String, Integer> toolCallCounts = new java.util.HashMap<>();
        while (true) {
            if (consumeAbort(sessionId)) {
                var asst = store.appendAssistant(sessionId);
                store.addPart(asst.id(), new Part.Text(
                        "用户已中断（interrupt），回合终止。", true, false));
                return;
            }
            if (!checkClaim(sessionId)) {
                var asst = store.appendAssistant(sessionId);
                store.addPart(asst.id(), new Part.Text(
                        "writer claim 已被抢占，回合中止。", true, false));
                return;
            }
            if (++step > MAX_STEPS) {
                var asst = store.appendAssistant(sessionId);
                store.addPart(asst.id(), new Part.Text(
                        "达到最大步数限制（" + MAX_STEPS + "），回合终止。", true, false));
                return;
            }
            List<com.lobster.model.Message> history = store.loadActive(sessionId);

            // 溢出检测：估算 token 超阈值 -> 自动压缩（保留尾部）
            long limit = contextLimitHolder > 0 ? contextLimitHolder : contextLimit;
            if (TokenEstimator.estimate(history) > limit * COMPACTION_TRIGGER) {
                history = autoCompact(sessionId, history);
            }

            // 终止检查：最后一条 assistant 无未完成工具则结束
            var last = history.isEmpty() ? null : history.get(history.size() - 1);
            if (last != null && "assistant".equals(last.role()) && last.parts().stream()
                    .noneMatch(p -> p instanceof Part.Tool)) {
                return; // 纯文本回复，回合结束
            }

            // assistant 消息（含工具结果的 user 回填后重新调用）
            var assistant = store.appendAssistant(sessionId);
            bus.publish(new com.lobster.event.LobsterEvent(Events.STEP_STARTED, sessionId,
                    OM.createObjectNode().put("assistantMessageId", assistant.id()), true));

            // 组装请求
            List<LlmProvider.ToolSpec> toolSpecs = toolSpecs();
            String system = promptAssembler.assemble(toolSpecs, java.nio.file.Path.of(
                    System.getProperty("user.dir")), skillNames, referenceNames);
            List<LlmProvider.ChatMsg> messages = toChatMessages(history, sessionId);

            // 消费 LLM 流
            List<LlmEvent.ToolCall> toolCalls = new ArrayList<>();
            StringBuilder textBuf = new StringBuilder();
            LlmEvent.Usage usage = new LlmEvent.Usage(0, 0);
            String finishReason = "stop";
            boolean errored = false;

            for (LlmEvent event : (Iterable<LlmEvent>) llm.stream(
                    new LlmProvider.LlmRequest(model, system, messages, toolSpecs, 0.7))::iterator) {
                switch (event) {
                    case LlmEvent.TextDelta d -> {
                        textBuf.append(d.text());
                        bus.publish(new com.lobster.event.LobsterEvent(Events.TEXT_DELTA, sessionId,
                                OM.createObjectNode().put("delta", d.text()), false));
                    }
                    case LlmEvent.ToolCall c -> toolCalls.add(c);
                    case LlmEvent.Finish f -> {
                        finishReason = f.reason();
                        usage = f.usage();
                    }
                    case LlmEvent.Error e -> {
                        errored = true;
                        store.addPart(assistant.id(), new Part.Text(
                                "LLM 错误: " + e.cause().getMessage(), false, false));
                    }
                }
            }
            if (errored) return;

            if (!textBuf.isEmpty()) {
                store.addPart(assistant.id(), new Part.Text(textBuf.toString(), false, false));
                bus.publish(new com.lobster.event.LobsterEvent(Events.TEXT_ENDED, sessionId,
                        OM.createObjectNode().put("text", textBuf.toString()), true));
            }

            // 执行工具（M1 顺序执行）
            for (LlmEvent.ToolCall call : toolCalls) {
                // doom loop 检测：同工具+同参数连续重复
                String key = call.name() + ":" + call.argumentsJson();
                int count = toolCallCounts.merge(key, 1, Integer::sum);
                if (count > DOOM_LOOP_THRESHOLD) {
                    store.addPart(assistant.id(), new Part.Tool(call.name(), call.callId(),
                            new Part.ToolState.Error("doom loop 检测：同参数重复调用 " + count + " 次，已熔断")));
                    publishToolFailed(sessionId, call, "doom loop 熔断");
                    var asst = store.appendAssistant(sessionId);
                    store.addPart(asst.id(), new Part.Text(
                            "doom loop 检测：" + call.name() + " 同参数重复调用 " + count + " 次，回合终止。", true, false));
                    return;
                }
                executeTool(sessionId, assistant.id(), call);
            }

            store.addPart(assistant.id(), new Part.StepFinish(
                    toolCalls.isEmpty() ? finishReason : "tool_calls",
                    usage.inputTokens(), usage.outputTokens(), 0));
            bus.publish(new com.lobster.event.LobsterEvent(Events.STEP_ENDED, sessionId,
                    OM.createObjectNode()
                            .put("finish", toolCalls.isEmpty() ? finishReason : "tool_calls")
                            .put("tokensInput", usage.inputTokens())
                            .put("tokensOutput", usage.outputTokens()), true));

            // 无工具调用 -> 回合结束
            if (toolCalls.isEmpty()) return;

            // 工具结果作为 user 消息回填（tool part 形式挂在回填消息上）
            var resultMsg = store.appendUser(sessionId, List.of());
            for (LlmEvent.ToolCall call : toolCalls) {
                var toolPart = findToolPart(assistant.id(), call.callId());
                if (toolPart != null) {
                    store.addPart(resultMsg.id(), toolPart);
                }
            }
        }
    }

    private void executeTool(String sessionId, String assistantMessageId, LlmEvent.ToolCall call) {
        // 钩子：tool.before（block 语义，退出码 2 拦截）
        if (hookEngine != null) {
            var before = hookEngine.fire(Events.TOOL_BEFORE, agentId, sessionId,
                    "{\"tool\":\"" + call.name() + "\",\"callId\":\"" + call.callId() + "\"}");
            if (before.blocked()) {
                store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                        new Part.ToolState.Error("被 hook 拦截: " + call.name())));
                publishToolFailed(sessionId, call, "hook 拦截");
                return;
            }
        }
        // Plan 模式权限裁剪：写/执行类工具禁用（write 例外：仅 plans/*.md）
        if (planMode.isToolDenied(sessionId, call.name())) {
            store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                    new Part.ToolState.Error("Plan 模式：工具 " + call.name() + " 已被裁剪禁用")));
            publishToolFailed(sessionId, call, "Plan 模式裁剪");
            return;
        }
        if (planMode.isPlan(sessionId) && "write".equals(call.name())) {
            String target = planWriteTarget(call);
            if (target == null || !target.replace('\\', '/').startsWith("plans/")
                    || !target.endsWith(".md")) {
                store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                        new Part.ToolState.Error("Plan 模式：write 仅允许 plans/*.md，实际: " + target)));
                publishToolFailed(sessionId, call, "Plan 模式 write 裁剪");
                return;
            }
        }

        Tool tool = tools.get(call.name());
        if (tool == null || !toolAllowed(call.name())) {
            store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                    new Part.ToolState.Error("未知工具或角色无权限: " + call.name())));
            publishToolFailed(sessionId, call, "未知工具/角色无权限");
            return;
        }

        // 权限：pattern 取工具名与首个参数值（M1 简化）
        String firstArg = firstArgValue(call);
        PermissionReply reply = permissions.ask(call.name(),
                List.of(call.name() + ":" + (firstArg == null ? "*" : firstArg), firstArg == null ? "*" : firstArg),
                sessionId);
        if (!reply.allowed()) {
            store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                    new Part.ToolState.Error("权限拒绝: " + reply.feedback())));
            publishToolFailed(sessionId, call, "权限拒绝");
            return;
        }

        store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                new Part.ToolState.Running(call.name(), null)));
        bus.publish(new com.lobster.event.LobsterEvent(Events.TOOL_CALLED, sessionId,
                OM.createObjectNode().put("callID", call.callId()).put("tool", call.name()), true));

        try {
            JsonNode args = OM.readTree(call.argumentsJson().isEmpty() ? "{}" : call.argumentsJson());
            var result = tool.execute(args, new ToolContext(
                    sessionId, assistantMessageId, agentId,
                    () -> {}, m -> {}, null));
            store.updateToolState(assistantMessageId, call.callId(),
                    new Part.ToolState.Completed(result.title(), result.output(), null));
            bus.publish(new com.lobster.event.LobsterEvent(Events.TOOL_SUCCESS, sessionId,
                    OM.createObjectNode()
                            .put("callID", call.callId())
                            .put("title", result.title())
                            .put("output", truncate(result.output(), 4000)), true));
            fireToolAfterHooks(sessionId, call, "success");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.updateToolState(assistantMessageId, call.callId(),
                    new Part.ToolState.Error(msg));
            publishToolFailed(sessionId, call, msg);
            fireToolAfterHooks(sessionId, call, "error");
        }
    }

    /** tool.after（after 语义，非阻断）+ skill/mcp 完成钩子（按工具名前缀派发）。 */
    private void fireToolAfterHooks(String sessionId, LlmEvent.ToolCall call, String outcome) {
        if (hookEngine == null) return;
        hookEngine.fire(Events.TOOL_AFTER, agentId, sessionId,
                "{\"tool\":\"" + call.name() + "\",\"callId\":\"" + call.callId() + "\",\"outcome\":\"" + outcome + "\"}");
        if (call.name().startsWith("skill")) {
            hookEngine.fire(Events.SKILL_COMPLETED, agentId, sessionId,
                    "{\"tool\":\"" + call.name() + "\",\"callId\":\"" + call.callId() + "\"}");
        } else if (call.name().startsWith("mcp")) {
            hookEngine.fire(Events.MCP_TOOL_COMPLETED, agentId, sessionId,
                    "{\"tool\":\"" + call.name() + "\",\"callId\":\"" + call.callId() + "\"}");
        }
    }

    /** Plan 模式 write 放行判定：取 file_path 参数。 */
    private String planWriteTarget(LlmEvent.ToolCall call) {
        try {
            JsonNode args = OM.readTree(call.argumentsJson().isEmpty() ? "{}" : call.argumentsJson());
            return args.path("file_path").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstArgValue(LlmEvent.ToolCall call) {
        try {
            JsonNode args = OM.readTree(call.argumentsJson().isEmpty() ? "{}" : call.argumentsJson());
            if (args.isObject()) {
                for (String key : List.of("command", "file_path", "pattern", "path", "prompt")) {
                    if (args.hasNonNull(key)) return args.get(key).asText();
                }
                var it = args.fields();
                if (it.hasNext()) return it.next().getValue().asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void publishToolFailed(String sessionId, LlmEvent.ToolCall call, String error) {
        bus.publish(new com.lobster.event.LobsterEvent(Events.TOOL_FAILED, sessionId,
                OM.createObjectNode()
                        .put("callID", call.callId())
                        .put("tool", call.name())
                        .put("error", error), true));
    }

    private void publishStatus(String sessionId, String status) {
        bus.publish(new com.lobster.event.LobsterEvent(Events.SESSION_STATUS, sessionId,
                OM.createObjectNode().put("type", status), false));
    }

    private void fireHook(String event, String sessionId, String payload) {
        if (hookEngine != null) {
            hookEngine.fire(event, agentId, sessionId, payload);
        }
    }

    private Part.Tool findToolPart(String assistantMessageId, String callId) {
        try {
            var msgOpt = store.lastMessage(""); // 占位不用
        } catch (Exception ignored) {}
        // 从 assistant 消息加载 tool part（通过 loadActive 的最后加载）
        return null; // M1 由 chat 消息直接读取，见 toChatMessages
    }

    private List<LlmProvider.ToolSpec> toolSpecs() {
        List<LlmProvider.ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools.all()) {
            if (!toolAllowed(t.id())) continue; // 角色过滤
            specs.add(new LlmProvider.ToolSpec(t.id(), t.description(), t.parameters()));
        }
        return specs;
    }

    private List<LlmProvider.ChatMsg> toChatMessages(List<com.lobster.model.Message> history, String sessionId) {
        List<LlmProvider.ChatMsg> out = new ArrayList<>();
        String planReminder = planMode.reminder(sessionId);
        for (int i = 0; i < history.size(); i++) {
            var m = history.get(i);
            if ("user".equals(m.role())) {
                String text = m.parts().stream()
                        .filter(p -> p instanceof Part.Text)
                        .map(p -> ((Part.Text) p).text())
                        .reduce("", (a, b) -> a + b);
                // Plan reminder 注入最后一条 user 消息（synthetic 后缀）
                if (planReminder != null && i == history.size() - 1 && !text.isEmpty()) {
                    text = text + "\n\n" + planReminder;
                }
                if (!text.isEmpty()) out.add(LlmProvider.ChatMsg.user(text));
                // 工具结果 part 转为 tool 消息
                for (Part p : m.parts()) {
                    if (p instanceof Part.Tool t && t.state() instanceof Part.ToolState.Completed c) {
                        out.add(LlmProvider.ChatMsg.toolResult(t.callId(), t.tool(),
                                truncate(c.output(), 8000)));
                    }
                }
            } else if ("assistant".equals(m.role())) {
                String text = m.parts().stream()
                        .filter(p -> p instanceof Part.Text)
                        .map(p -> ((Part.Text) p).text())
                        .reduce("", (a, b) -> a + b);
                if (!text.isEmpty()) out.add(LlmProvider.ChatMsg.assistant(text));
                // assistant 中的工具调用需要带 tool_calls 结构（M1 简化：以文本注释表达）
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...truncated...";
    }
}
