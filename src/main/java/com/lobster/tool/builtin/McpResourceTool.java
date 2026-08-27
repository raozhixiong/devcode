package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.lobster.mcp.McpClient;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** MCP 资源工具（FR-A4-7 三件套）：list / read / templates，按 server 聚合。 */
public class McpResourceTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();
    private final String server;
    private final McpClient client;

    public McpResourceTool(String server, McpClient client) {
        this.server = server;
        this.client = client;
    }

    @Override public String id() { return "mcp__" + server + "__resource"; }

    @Override public String description() {
        return "访问 MCP 服务器 '" + server + "' 的资源：list（列出）/ read（读取 uri）/ templates（模板列表）。";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("list", "read", "templates")),
                        "uri", Map.of("type", "string")),
                "required", List.of("action"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String action = args.path("action").asText("list");
        switch (action) {
            case "read" -> {
                String uri = args.path("uri").asText();
                return ToolResult.of(id(), client.readResource(uri));
            }
            case "templates" -> {
                ArrayNode arr = OM.valueToTree(client.listResourceTemplates());
                return ToolResult.of(id(), arr.toString());
            }
            default -> {
                ArrayNode arr = OM.valueToTree(client.listResources());
                return ToolResult.of(id(), arr.toString());
            }
        }
    }
}
