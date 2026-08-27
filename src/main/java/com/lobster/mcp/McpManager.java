package com.lobster.mcp;

import com.lobster.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 服务器管理器：连接各 server，汇总其工具与资源工具。 */
public class McpManager {

    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private final List<Tool> tools = new ArrayList<>();

    public void connect(String name, McpTransport transport) throws Exception {
        McpClient client = new McpClient(name, transport);
        client.initialize();
        for (var ts : client.listTools()) {
            tools.add(new com.lobster.tool.builtin.McpTool(name, ts, client));
        }
        tools.add(new com.lobster.tool.builtin.McpResourceTool(name, client));
        clients.put(name, client);
    }

    public List<Tool> tools() { return List.copyOf(tools); }

    public void closeAll() { clients.values().forEach(McpClient::close); }
}
