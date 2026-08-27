package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.mcp.McpClient;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把 MCP server 的某个 tool 包装为 Lobster Tool（id 形如 mcp__<server>__<tool>）。 */
public class McpTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();
    private final String server;
    private final String toolName;
    private final String description;
    private final JsonNode schema;
    private final McpClient client;

    public McpTool(String server, McpClient.ToolSpec spec, McpClient client) {
        this.server = server;
        this.toolName = spec.name();
        this.description = spec.description();
        this.schema = spec.inputSchema();
        this.client = client;
    }

    @Override public String id() { return "mcp__" + server + "__" + toolName; }

    @Override public String description() { return description; }

    @Override public Map<String, Object> parameters() {
        if (schema != null && schema.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            schema.fields().forEachRemaining(e ->
                    m.put(e.getKey(), OM.convertValue(e.getValue(), Object.class)));
            return m;
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        var res = client.callTool(toolName, args);
        return ToolResult.of(id(), res.content().isEmpty() ? "(no output)" : res.content());
    }
}
