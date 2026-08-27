package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.agent.AgentLoop;
import com.lobster.model.Part;
import com.lobster.store.MessageStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * task 子代理工具：派生子会话跑独立 loop，返回最终文本摘要。
 * 深度限制 2 层，防止递归爆炸。
 */
public class TaskTool implements Tool {

    private static final int MAX_DEPTH = 2;
    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private final MessageStore store;
    private final AgentLoop parent;

    public TaskTool(MessageStore store, AgentLoop parent) {
        this.store = store;
        this.parent = parent;
    }

    @Override public String id() { return "task"; }

    @Override public String description() {
        return "Launch a sub-agent with a fresh context to complete a subtask. Returns the sub-agent's final answer.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "description", Map.of("type", "string", "description", "Subtask description (max 100 chars)"),
                        "prompt", Map.of("type", "string", "description", "Full instructions for the sub-agent")),
                "required", List.of("description", "prompt"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        int d = depth.get();
        if (d >= MAX_DEPTH) {
            return ToolResult.of("Task", "Error: sub-agent depth limit (" + MAX_DEPTH + ") reached");
        }
        String description = args.path("description").asText("subtask");
        String prompt = args.path("prompt").asText();
        if (description.length() > 100) description = description.substring(0, 100);

        // 子会话（kind=task, 关联父会话）
        var child = store.createSession("task-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                "task", System.getProperty("user.dir"));
        store.appendUser(child.id(), List.of(new Part.Text(prompt, false, false)));

        depth.set(d + 1);
        try {
            parent.run(child.id());
        } finally {
            depth.set(d);
        }

        // 取子会话最后一条 assistant 文本
        String answer = store.loadActive(child.id()).stream()
                .filter(m -> "assistant".equals(m.role()))
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Text)
                .map(p -> ((Part.Text) p).text())
                .reduce("", (a, b) -> b); // 取最后一个文本
        if (answer.isEmpty()) answer = "(sub-agent produced no text output)";
        return ToolResult.of("Task: " + description, answer);
    }
}
