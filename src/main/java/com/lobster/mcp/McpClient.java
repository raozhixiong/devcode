package com.lobster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端（FR-A4-7）：JSON-RPC 2.0 over McpTransport。
 * 支持 initialize / tools/list / tools/call / resources/list|read|templates/list。
 */
public class McpClient implements AutoCloseable {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final long REQUEST_TIMEOUT_MS = 15_000;

    private final String serverName;
    private final McpTransport transport;
    private final AtomicLong idSeq = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Thread reader;
    private volatile boolean running = true;

    public record ToolSpec(String name, String description, JsonNode inputSchema) {}
    public record CallResult(String content, boolean isError) {}

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.reader = new Thread(this::readLoop, "mcp-reader-" + serverName);
        this.reader.setDaemon(true);
        this.reader.start();
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = transport.receive()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode msg = OM.readTree(line);
                    if (msg.has("id")) {
                        long id = msg.get("id").asLong();
                        CompletableFuture<JsonNode> f = pending.remove(id);
                        if (f != null) {
                            if (msg.has("error")) {
                                f.completeExceptionally(new IOException("MCP error: " + msg.get("error")));
                            } else {
                                f.complete(msg.path("result"));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private JsonNode request(String method, JsonNode params) throws Exception {
        long id = idSeq.getAndIncrement();
        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params != null ? params : OM.createObjectNode());
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        transport.send(OM.writeValueAsString(req));
        try {
            return f.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(id);
            throw new IOException("MCP 请求 " + method + " 失败: " + e.getMessage(), e);
        }
    }

    private void notify(String method, JsonNode params) {
        try {
            ObjectNode n = OM.createObjectNode();
            n.put("jsonrpc", "2.0");
            n.put("method", method);
            n.set("params", params != null ? params : OM.createObjectNode());
            transport.send(OM.writeValueAsString(n));
        } catch (IOException ignored) {}
    }

    public void initialize() throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode cli = OM.createObjectNode();
        cli.put("name", "lobster");
        cli.put("version", "0.1");
        params.set("clientInfo", cli);
        params.set("capabilities", OM.createObjectNode());
        request("initialize", params);
        notify("notifications/initialized", null);
    }

    public List<ToolSpec> listTools() throws Exception {
        JsonNode res = request("tools/list", OM.createObjectNode());
        List<ToolSpec> out = new ArrayList<>();
        JsonNode tools = res.path("tools");
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                out.add(new ToolSpec(t.path("name").asText(),
                        t.path("description").asText(""), t.path("inputSchema")));
            }
        }
        return out;
    }

    public CallResult callTool(String name, JsonNode args) throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("name", name);
        params.set("arguments", args != null ? args : OM.createObjectNode());
        JsonNode res = request("tools/call", params);
        StringBuilder sb = new StringBuilder();
        JsonNode content = res.path("content");
        if (content.isArray()) {
            for (JsonNode c : content) {
                if ("text".equals(c.path("type").asText())) sb.append(c.path("text").asText());
            }
        }
        return new CallResult(sb.toString(), res.path("isError").asBoolean(false));
    }

    public List<JsonNode> listResources() throws Exception {
        JsonNode res = request("resources/list", OM.createObjectNode());
        List<JsonNode> out = new ArrayList<>();
        JsonNode r = res.path("resources");
        if (r.isArray()) for (JsonNode x : r) out.add(x);
        return out;
    }

    public String readResource(String uri) throws Exception {
        ObjectNode params = OM.createObjectNode();
        params.put("uri", uri);
        JsonNode res = request("resources/read", params);
        StringBuilder sb = new StringBuilder();
        JsonNode content = res.path("contents");
        if (content.isArray()) for (JsonNode c : content) sb.append(c.path("text").asText());
        return sb.toString();
    }

    public List<JsonNode> listResourceTemplates() throws Exception {
        JsonNode res = request("resources/templates/list", OM.createObjectNode());
        List<JsonNode> out = new ArrayList<>();
        JsonNode r = res.path("resourceTemplates");
        if (r.isArray()) for (JsonNode x : r) out.add(x);
        return out;
    }

    public String serverName() { return serverName; }

    public void close() {
        running = false;
        try { transport.close(); } catch (Exception ignored) {}
        reader.interrupt();
    }
}
