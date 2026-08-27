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

    public AgentLoop(MessageStore store, EventBus bus, ToolRegistry tools,
                     PermissionEngine permissions, LlmProvider llm,
                     String agentId, String model) {
        this.store = store;
        this.bus = bus;
        this.tools = tools;
        this.permissions = permissions;
        this.llm = llm;
        this.agentId = agentId;
        this.model = model;
        this.promptAssembler = new PromptAssembler(agentId, model);
    }

    public void run(String sessionId) {
        publishStatus(sessionId, "busy");
        try {
            runLoop(sessionId);
        } finally {
            bus.publish(new com.lobster.event.LobsterEvent(
                    Events.SESSION_IDLE, sessionId,
                    OM.createObjectNode(), false));
            publishStatus(sessionId, "idle");
        }
    }

    private static final int MAX_STEPS = 50;

    private void runLoop(String sessionId) {
        int step = 0;
        while (true) {
            if (++step > MAX_STEPS) {
                var asst = store.appendAssistant(sessionId);
                store.addPart(asst.id(), new Part.Text(
                        "达到最大步数限制（" + MAX_STEPS + "），回合终止。", true, false));
                return;
            }
            List<com.lobster.model.Message> history = store.loadActive(sessionId);

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
            String system = promptAssembler.assemble(toolSpecs);
            List<LlmProvider.ChatMsg> messages = toChatMessages(history);

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
        Tool tool = tools.get(call.name());
        if (tool == null) {
            store.addPart(assistantMessageId, new Part.Tool(call.name(), call.callId(),
                    new Part.ToolState.Error("未知工具: " + call.name())));
            publishToolFailed(sessionId, call, "未知工具");
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
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.updateToolState(assistantMessageId, call.callId(),
                    new Part.ToolState.Error(msg));
            publishToolFailed(sessionId, call, msg);
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
            specs.add(new LlmProvider.ToolSpec(t.id(), t.description(), t.parameters()));
        }
        return specs;
    }

    private List<LlmProvider.ChatMsg> toChatMessages(List<com.lobster.model.Message> history) {
        List<LlmProvider.ChatMsg> out = new ArrayList<>();
        for (var m : history) {
            if ("user".equals(m.role())) {
                String text = m.parts().stream()
                        .filter(p -> p instanceof Part.Text)
                        .map(p -> ((Part.Text) p).text())
                        .reduce("", (a, b) -> a + b);
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
