package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** Todo 清单维护（对齐 OpenCode TodoWrite：全量替换）。 */
public class TodoTool implements Tool {

    @Override public String id() { return "todo"; }

    @Override public String description() {
        return "Maintain the session task list. Pass the full list each call "
                + "([{content, status: pending|in_progress|completed, priority}]).";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of("type", "array", "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "content", Map.of("type", "string"),
                                        "status", Map.of("type", "string",
                                                "enum", List.of("pending", "in_progress", "completed")),
                                        "priority", Map.of("type", "string",
                                                "enum", List.of("high", "medium", "low")))))),
                "required", List.of("todos"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
        var todos = args.withArray("todos");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < todos.size(); i++) {
            var t = todos.get(i);
            String mark = switch (t.path("status").asText("pending")) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(mark).append(' ').append(t.path("content").asText()).append('\n');
        }
        return ToolResult.of("TodoWrite", todos.size() + " todos:\n" + sb);
    }
}
