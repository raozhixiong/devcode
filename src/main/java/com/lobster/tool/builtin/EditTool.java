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

/** 精确字符串替换编辑（对齐 OpenCode Edit 语义）。 */
public class EditTool implements Tool {

    @Override public String id() { return "edit"; }

    @Override public String description() {
        return "Edit a file by replacing an exact string. old_string must be unique in the file "
                + "unless replace_all is true.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string"),
                        "old_string", Map.of("type", "string"),
                        "new_string", Map.of("type", "string"),
                        "replace_all", Map.of("type", "boolean")),
                "required", List.of("file_path", "old_string", "new_string"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
        Path path = Path.of(args.get("file_path").asText());
        String oldStr = args.get("old_string").asText();
        String newStr = args.get("new_string").asText();
        boolean all = args.path("replace_all").asBoolean(false);

        if (oldStr.isEmpty()) {
            throw new IllegalArgumentException("old_string 不能为空");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int first = content.indexOf(oldStr);
        if (first < 0) {
            throw new IllegalArgumentException("old_string 未找到: " + abbreviate(oldStr));
        }
        if (!all && content.indexOf(oldStr, first + 1) >= 0) {
            throw new IllegalArgumentException("old_string 不唯一（出现多次），请提供更长上下文或设 replace_all=true");
        }
        String updated = all ? content.replace(oldStr, newStr)
                : content.substring(0, first) + newStr + content.substring(first + oldStr.length());
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return ToolResult.of("Edit " + path.getFileName(), "Replaced " + (all ? "all" : "1")
                + " occurrence(s) in " + path);
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
