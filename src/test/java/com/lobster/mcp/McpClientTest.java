package com.lobster.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    /** 脚本化传输：收到请求时按 method 回放预设的 result JSON（注入请求 id）。 */
    static class ScriptedTransport implements McpTransport {
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final Map<String, String> script;
        private final ObjectMapper om = new ObjectMapper();
        private final List<String> sent = new java.util.ArrayList<>();

        ScriptedTransport(Map<String, String> script) { this.script = script; }

        @Override
        public void send(String jsonLine) throws IOException {
            sent.add(jsonLine);
            try {
                var m = om.readTree(jsonLine);
                String method = m.path("method").asText();
                String resultJson = script.get(method);
                if (resultJson != null) {
                    long id = m.path("id").asLong();
                    inbox.put("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public String receive() throws IOException {
            try { return inbox.take(); } catch (InterruptedException e) { throw new IOException(e); }
        }

        @Override
        public void close() {}
    }

    @Test
    void initializeAndListTools() throws Exception {
        var script = Map.of(
                "initialize", "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}",
                "tools/list", "{\"tools\":[{\"name\":\"foo\",\"description\":\"does foo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}}]}");
        try (var client = new McpClient("srv", new ScriptedTransport(script))) {
            client.initialize();
            var tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("foo", tools.get(0).name());
            assertEquals("does foo", tools.get(0).description());
        }
    }

    @Test
    void callToolReturnsContent() throws Exception {
        var script = Map.of(
                "initialize", "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}",
                "tools/call", "{\"content\":[{\"type\":\"text\",\"text\":\"hello from mcp\"}],\"isError\":false}");
        try (var client = new McpClient("srv", new ScriptedTransport(script))) {
            client.initialize();
            var res = client.callTool("foo", null);
            assertEquals("hello from mcp", res.content());
            assertFalse(res.isError());
        }
    }

    @Test
    void resourceListAndRead() throws Exception {
        var script = Map.of(
                "initialize", "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}",
                "resources/list", "{\"resources\":[{\"uri\":\"file:///a\",\"name\":\"a\"}]}",
                "resources/read", "{\"contents\":[{\"uri\":\"file:///a\",\"text\":\"body\"}]}");
        try (var client = new McpClient("srv", new ScriptedTransport(script))) {
            client.initialize();
            assertEquals(1, client.listResources().size());
            assertEquals("body", client.readResource("file:///a"));
        }
    }
}
