package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** 目录列表（对齐 OpenCode Ls）。 */
public class ListTool implements Tool {

    @Override public String id() { return "ls"; }

    @Override public String description() {
        return "List directory entries with type markers (d/ file).";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string")),
                "required", List.of("path"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(args.get("path").asText());
        if (!java.nio.file.Files.isDirectory(dir)) {
            return ToolResult.of("Ls", "Error: not a directory: " + dir);
        }
        StringBuilder sb = new StringBuilder();
        try (var stream = java.nio.file.Files.list(dir)) {
            stream.sorted().forEach(p -> {
                try {
                    sb.append(java.nio.file.Files.isDirectory(p) ? "d " : "  ")
                      .append(p.getFileName()).append('\n');
                } catch (Exception ignored) {}
            });
        }
        return ToolResult.of("Ls " + dir.getFileName(), sb.isEmpty() ? "(empty)" : sb.toString());
    }
}
