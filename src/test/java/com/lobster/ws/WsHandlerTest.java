package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-t11"})
class WsHandlerTest {
    @Autowired Environment env;
    private final com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void sendAndReceiveEvents() throws Exception {
        int port = env.getProperty("local.server.port", Integer.class);
        var client = new StandardWebSocketClient();
        var received = new CopyOnWriteArrayList<JsonNode>();
        var sessionFuture = new CompletableFuture<WebSocketSession>();
        var done = new CompletableFuture<Void>();

        client.execute(new TextWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession s) { sessionFuture.complete(s); }
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                JsonNode n = om.readTree(m.getPayload());
                received.add(n);
                if ("session.status".equals(n.path("event").asText())
                        && n.path("payload").path("type").asText().equals("idle")) {
                    done.complete(null);
                }
            }
        }, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);

        var s = sessionFuture.get(5, TimeUnit.SECONDS);
        s.sendMessage(new TextMessage("{\"type\":\"req\",\"id\":\"1\",\"method\":\"chat.send\","
                + "\"params\":{\"sessionKey\":\"main\",\"text\":\"hi\"},\"idempotencyKey\":\"k1\"}"));

        done.get(30, TimeUnit.SECONDS);
        assertTrue(received.stream().anyMatch(n -> "res".equals(n.path("type").asText()) && n.path("ok").asBoolean()));
        assertTrue(received.stream().anyMatch(n -> "event".equals(n.path("type").asText())));
    }

    @Test
    void chatHistoryReturnsMessages() throws Exception {
        int port = env.getProperty("local.server.port", Integer.class);
        var client = new StandardWebSocketClient();
        var received = new CopyOnWriteArrayList<JsonNode>();
        var sessionFuture = new CompletableFuture<WebSocketSession>();
        var done = new CompletableFuture<Void>();

        client.execute(new TextWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession s) { sessionFuture.complete(s); }
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                JsonNode n = om.readTree(m.getPayload());
                received.add(n);
                if ("res".equals(n.path("type").asText()) && "2".equals(n.path("id").asText())) {
                    done.complete(null);
                }
            }
        }, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);

        var s = sessionFuture.get(5, TimeUnit.SECONDS);
        // 先发消息触发 loop（Mock 直接回复）
        s.sendMessage(new TextMessage("{\"type\":\"req\",\"id\":\"1\",\"method\":\"chat.send\","
                + "\"params\":{\"sessionKey\":\"hist\",\"text\":\"hello\"}}"));
        // 轮询等 run 完成再查历史
        for (int i = 0; i < 50 && !Thread.currentThread().isInterrupted(); i++) {
            Thread.sleep(200);
            s.sendMessage(new TextMessage("{\"type\":\"req\",\"id\":\"2\",\"method\":\"chat.history\","
                    + "\"params\":{\"sessionKey\":\"hist\"}}"));
            try { done.get(400, TimeUnit.MILLISECONDS); break; } catch (Exception ignored) {}
        }
        done.get(5, TimeUnit.SECONDS);
        var historyRes = received.stream()
                .filter(n -> "res".equals(n.path("type").asText()) && "2".equals(n.path("id").asText()))
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(historyRes.path("ok").asBoolean());
        assertTrue(historyRes.path("payload").path("messages").size() >= 2);
    }
}
