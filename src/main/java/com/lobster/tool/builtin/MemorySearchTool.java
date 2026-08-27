package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.store.MemoryStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 记忆搜索工具（对齐 FR-D-3）：搜索 episodic/curated 记忆。
 */
public class MemorySearchTool implements Tool {

    private final MemoryStore memory;

    public MemorySearchTool(MemoryStore memory) {
        this.memory = memory;
    }

    @Override public String id() { return "memory_search"; }

    @Override public String description() {
        return "Search past memories (episodic diary + curated MEMORY.md). "
                + "Use this to recall what happened in previous sessions.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query"),
                        "limit", Map.of("type", "integer", "description", "Max results (default 10)")),
                "required", List.of("query"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String query = args.path("query").asText();
        int limit = args.path("limit").asInt(10);
        var results = memory.search(query, limit);
        if (results.isEmpty()) {
            return ToolResult.of("memory_search: " + query, "未找到匹配记忆。");
        }
        StringBuilder sb = new StringBuilder();
        for (var c : results) {
            sb.append("--- memory chunk ").append(c.id()).append(" (")
                    .append(c.originClass()).append(") ---\n")
                    .append(c.content()).append("\n\n");
        }
        return ToolResult.of("memory_search: " + query, sb.toString());
    }
}
