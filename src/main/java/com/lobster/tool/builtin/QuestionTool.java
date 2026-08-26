package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** 向用户提问（M1：阻塞等待回复；M2 接 WS question 流）。 */
public class QuestionTool implements Tool {

    @Override public String id() { return "question"; }

    @Override public String description() {
        return "Ask the user a question with optional choices. Use when critical information is missing.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string"),
                        "choices", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("question"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
        // M1：无 UI 通道时返回提示，模型应基于现有信息继续
        String q = args.get("question").asText();
        return ToolResult.of("Question", "用户暂时不可用（M1 无交互通道）。问题: " + q
                + "。请基于现有信息继续或说明阻塞点。");
    }
}
