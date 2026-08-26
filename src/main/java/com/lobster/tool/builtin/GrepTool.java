package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 正则内容搜索（对齐 OpenCode Grep：pattern + include 过滤，返回 文件:行号: 内容）。 */
public class GrepTool implements Tool {

    @Override public String id() { return "grep"; }

    @Override public String description() {
        return "Search file contents with a regex. Returns file:line: text matches (max 200).";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string"),
                        "path", Map.of("type", "string", "description", "Search root (optional)"),
                        "include", Map.of("type", "string", "description", "File name glob filter, e.g. *.java")),
                "required", List.of("pattern"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
        Pattern regex = Pattern.compile(args.get("pattern").asText());
        Path root = args.hasNonNull("path") ? Path.of(args.get("path").asText()) : Path.of(".");
        String include = args.hasNonNull("include") ? args.get("include").asText() : null;

        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> include == null || matchesName(p, include))
                .forEach(p -> scan(p, regex, matches));
        }
        return ToolResult.of("Grep " + args.get("pattern").asText(),
                matches.isEmpty() ? "(no matches)" : String.join("\n", matches));
    }

    private void scan(Path p, Pattern regex, List<String> out) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && out.size() < 200; i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    out.add(p + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        } catch (Exception ignored) {
            // 二进制或不可读文件跳过
        }
    }

    private boolean matchesName(Path p, String include) {
        String name = p.getFileName().toString();
        if (include.startsWith("*.")) return name.endsWith(include.substring(1));
        return name.equals(include);
    }
}
