package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 权限 ask 走 WS：permission.asked 事件 -> permission.respond -> replied。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-perm"})
class PermissionWsTest {

    static final ObjectMapper OM = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired org.springframework.context.ApplicationContext ctx;

    @Test
    void askedAndRespondFlow() throws Exception {
        var engine = ctx.getBean(com.lobster.permission.PermissionEngine.class);
        var bus = ctx.getBean(com.lobster.event.EventBus.class);

        List<JsonNode> frames = new ArrayList<>();
        CompletableFuture<JsonNode> asked = new CompletableFuture<>();
        CompletableFuture<JsonNode> replied = new CompletableFuture<>();

        var client = new org.springframework.web.socket.client.standard.StandardWebSocketClient();
        var sessionFuture = client.execute(new org.springframework.web.socket.WebSocketHandler() {
            @Override public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession s) {}
            @Override public void handleMessage(org.springframework.web.socket.WebSocketSession s,
                                                org.springframework.web.socket.WebSocketMessage<?> m) throws Exception {
                JsonNode f = OM.readTree(((org.springframework.web.socket.TextMessage) m).getPayload());
                frames.add(f);
                if ("event".equals(f.path("type").asText())) {
                    if ("permission.asked".equals(f.path("event").asText())) asked.complete(f);
                    if ("permission.replied".equals(f.path("event").asText())) replied.complete(f);
                }
            }
            @Override public void handleTransportError(org.springframework.web.socket.WebSocketSession s, Throwable t) {}
            @Override public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession s,
                                                        org.springframework.web.socket.CloseStatus c) {}
            @Override public boolean supportsPartialMessages() { return false; }
        }, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
        var s = sessionFuture;

        // 异步触发 ask（write 无匹配 ALLOW -> ASK 挂起 -> 事件）
        var replyFuture = CompletableFuture.supplyAsync(() ->
                engine.ask("write", List.of("write:test.txt", "test.txt"), "ses_perm_1"));
        JsonNode askedFrame = asked.get(5, TimeUnit.SECONDS);
        assertEquals("permission.asked", askedFrame.path("event").asText());
        String requestId = askedFrame.path("payload").path("requestId").asText();
        assertFalse(requestId.isEmpty());

        // 模拟用户回复 ALLOW_ALWAYS
        s.sendMessage(new org.springframework.web.socket.TextMessage(
                "{\"type\":\"req\",\"id\":\"p1\",\"method\":\"permission.respond\","
                        + "\"params\":{\"requestId\":\"" + requestId + "\",\"decision\":\"ALLOW_ALWAYS\"}}"));

        var reply = replyFuture.get(5, TimeUnit.SECONDS);
        assertTrue(reply.allowed());
        assertEquals(com.lobster.tool.PermissionReply.Decision.ALLOW_ALWAYS, reply.decision());

        JsonNode repliedFrame = replied.get(5, TimeUnit.SECONDS);
        assertEquals("permission.replied", repliedFrame.path("event").asText());

        // ALLOW_ALWAYS 后同 pattern 规则生效（approve 规则 findLast 优先）
        var again = engine.ask("write", List.of("write:test.txt", "test.txt"));
        assertTrue(again.allowed());

        s.close();
    }
}
