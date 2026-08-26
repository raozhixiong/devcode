package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Glob 文件匹配（pattern 如 &#42;&#42;/*.java）。 */
public class GlobTool implements Tool {

    @Override public String id() { return "glob"; }

    @Override public String description() {
        return "Find files by glob pattern (e.g. **/*.java). Returns matching paths sorted by modification time.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string"),
                        "path", Map.of("type", "string", "description", "Search root (optional, default cwd)")),
                "required", List.of("pattern"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
        String pattern = args.get("pattern").asText();
        Path root = args.hasNonNull("path") ? Path.of(args.get("path").asText()) : Path.of(".");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // Java glob "**/*.x" 不匹配根级文件，补充一个 "*.x" 匹配器
        PathMatcher loose = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;

        List<Path> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (matcher.matches(Path.of(rel)) || matcher.matches(p.getFileName())
                            || (loose != null && loose.matches(p.getFileName()))) {
                        hits.add(p);
                    }
                });
        }
        hits.sort((a, b) -> {
            try {
                return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                        Files.getLastModifiedTime(a).toMillis());
            } catch (IOException e) {
                return 0;
            }
        });
        if (hits.size() > 500) hits.subList(500, hits.size()).clear();

        StringBuilder sb = new StringBuilder();
        for (Path p : hits) sb.append(p).append('\n');
        return ToolResult.of("Glob " + pattern, sb.isEmpty() ? "(no matches)" : sb.toString());
    }
}
