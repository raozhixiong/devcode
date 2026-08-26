package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 读取文件，行号前缀格式（对齐 OpenCode："N: content"，截断 2000 行/50KB）。 */
public class ReadTool implements Tool {

    static final int MAX_LINES = 2000;
    static final int MAX_BYTES = 51200;

    @Override public String id() { return "read"; }

    @Override public String description() {
        return "Read a text file. Returns content prefixed with line numbers (N: content). "
                + "Default truncates at 2000 lines or 50KB.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "Absolute file path"),
                        "offset", Map.of("type", "integer", "description", "1-based start line (optional)"),
                        "limit", Map.of("type", "integer", "description", "Max lines to read (optional)")),
                "required", List.of("file_path"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
        Path path = Path.of(args.get("file_path").asText());
        if (!Files.isRegularFile(path)) {
            return ToolResult.of("Read " + path.getFileName(), "Error: file not found: " + path);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int offset = args.hasNonNull("offset") ? Math.max(1, args.get("offset").asInt()) : 1;
        int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : MAX_LINES;
        int from = Math.min(offset - 1, lines.size());
        int to = Math.min(from + Math.min(limit, MAX_LINES), lines.size());

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            if (sb.length() > MAX_BYTES) {
                sb.append("...truncated at 50KB...\n");
                break;
            }
        }
        if (to < lines.size() && sb.indexOf("truncated") < 0) {
            sb.append("...").append(lines.size() - to).append(" lines truncated...\n");
        }
        return ToolResult.of("Read " + path.getFileName(), sb.toString());
    }
}
