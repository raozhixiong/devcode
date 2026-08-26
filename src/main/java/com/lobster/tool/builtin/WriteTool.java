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

/** 写文件（整体覆盖）。 */
public class WriteTool implements Tool {

    @Override public String id() { return "write"; }

    @Override public String description() {
        return "Write content to a file (overwrites existing content). Parent directories are created.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string"),
                        "content", Map.of("type", "string")),
                "required", List.of("file_path", "content"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws IOException {
        Path path = Path.of(args.get("file_path").asText());
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, args.get("content").asText(), StandardCharsets.UTF_8);
        return ToolResult.of("Write " + path.getFileName(), "Wrote " + Files.size(path) + " bytes to " + path);
    }
}
