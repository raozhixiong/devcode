package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.question.QuestionEngine;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问并阻塞等待回复。
 * 采用 CompletableFuture 挂起/恢复模式（与 PermissionEngine 同构）。
 */
public class QuestionTool implements Tool {

    private final QuestionEngine engine;

    public QuestionTool(QuestionEngine engine) {
        this.engine = engine;
    }

    @Override public String id() { return "question"; }

    @Override public String description() {
        return "Ask the user a question with optional choices. Blocks until the user replies. Use when critical information is missing and you cannot proceed without user input.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "description", "The question to ask the user"),
                        "choices", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Optional list of choices the user can pick from")),
                "required", List.of("question"));
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String question = args.get("question").asText();
        List<String> choices = new ArrayList<>();
        JsonNode choicesNode = args.path("choices");
        if (choicesNode.isArray()) {
            choicesNode.forEach(n -> choices.add(n.asText()));
        }
        String answer = engine.ask(question, choices, ctx.sessionId());
        return ToolResult.of("Question", answer);
    }
}
