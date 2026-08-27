package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 认证集成测试：bootstrap -> login -> connect 鉴权 -> auth gate -> 设备配对。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Autowired Environment env;
    private final ObjectMapper om = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var dir = Path.of("target/test-state-auth");
        deleteRecursively(dir);
        registry.add("lobster.state-dir", () -> dir.toString());
    }

    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception e) { /* ignore */ } });
        } catch (Exception e) { /* ignore */ }
    }

    private record WsClient(WebSocketSession session, CopyOnWriteArrayList<JsonNode> received) {}

    private WsClient connect(int port) throws Exception {
        var client = new StandardWebSocketClient();
        var received = new CopyOnWriteArrayList<JsonNode>();
        var future = new CompletableFuture<WebSocketSession>();
        client.execute(new TextWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession s) { future.complete(s); }
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                received.add(om.readTree(m.getPayload()));
            }
        }, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
        return new WsClient(future.get(5, TimeUnit.SECONDS), received);
    }

    private JsonNode sendReq(WsClient c, String id, String method, String paramsJson) throws Exception {
        String frame = "{\"type\":\"req\",\"id\":\"" + id + "\",\"method\":\"" + method + "\""
                + (paramsJson != null ? ",\"params\":" + paramsJson : "") + "}";
        c.session().sendMessage(new TextMessage(frame));
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            for (var n : c.received()) {
                if ("res".equals(n.path("type").asText()) && id.equals(n.path("id").asText()))
                    return n;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timeout waiting for res id=" + id + " received=" + c.received());
    }

    private JsonNode waitForEvent(WsClient c, String eventName, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (var n : c.received()) {
                if ("event".equals(n.path("type").asText()) && eventName.equals(n.path("event").asText()))
                    return n;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timeout waiting for event=" + eventName);
    }

    @Test
    void fullAuthFlow() throws Exception {
        int port = env.getProperty("local.server.port", Integer.class);

        // 1. 首次连接（无用户 → 免鉴权 auto-connect）
        var c1 = connect(port);
        Thread.sleep(500);
        var connectRes = sendReq(c1, "auto-conn", "connect", null);
        assertTrue(connectRes.path("ok").asBoolean());
        assertFalse(connectRes.path("payload").path("authRequired").asBoolean());

        // 2. auth.bootstrap → 创建管理员 + 获取 token
        var bsRes = sendReq(c1, "b1", "auth.bootstrap",
                "{\"username\":\"admin\",\"displayName\":\"Admin\",\"password\":\"pass123\",\"role\":\"admin\"}");
        assertTrue(bsRes.path("ok").asBoolean());
        String token = bsRes.path("payload").path("token").asText();
        assertTrue(token.startsWith("lst_"));
        assertEquals("admin", bsRes.path("payload").path("role").asText());

        // 3. auth.users.list（bootstrap 自动认证 → 可调保护方法）
        var listRes = sendReq(c1, "b2", "auth.users.list", null);
        assertTrue(listRes.path("ok").asBoolean());
        assertEquals(1, listRes.path("payload").path("users").size());
        assertEquals("admin", listRes.path("payload").path("users").get(0).path("username").asText());

        // 4. auth.login → 新 token
        var loginRes = sendReq(c1, "b3", "auth.login",
                "{\"username\":\"admin\",\"password\":\"pass123\"}");
        assertTrue(loginRes.path("ok").asBoolean());
        String loginToken = loginRes.path("payload").path("token").asText();
        assertTrue(loginToken.startsWith("lst_"));

        // 5. 错误密码 login 失败
        var badLogin = sendReq(c1, "b4", "auth.login",
                "{\"username\":\"admin\",\"password\":\"wrong\"}");
        assertFalse(badLogin.path("ok").asBoolean());
        assertEquals("UNAUTHORIZED", badLogin.path("error").path("code").asText());

        // 6. 新 WS 连接 → 应收到 connect.challenge（鉴权已启用）
        var c2 = connect(port);
        Thread.sleep(500);
        var challenge = waitForEvent(c2, "connect.challenge", 3000);
        assertTrue(challenge.path("payload").path("authRequired").asBoolean());

        // 7. connect 携带 token → 成功
        var connWithToken = sendReq(c2, "c1", "connect", "{\"token\":\"" + token + "\"}");
        assertTrue(connWithToken.path("ok").asBoolean());
        assertEquals("admin", connWithToken.path("payload").path("auth").path("username").asText());

        // 8. 认证后可调保护方法
        var tasksRes = sendReq(c2, "c2", "tasks.list", null);
        assertTrue(tasksRes.path("ok").asBoolean());

        // 9. 新 WS 不 connect 直接调保护方法 → UNAUTHORIZED
        var c3 = connect(port);
        Thread.sleep(500);
        waitForEvent(c3, "connect.challenge", 3000);
        var unauth = sendReq(c3, "u1", "tasks.list", null);
        assertFalse(unauth.path("ok").asBoolean());
        assertEquals("UNAUTHORIZED", unauth.path("error").path("code").asText());

        // 10. connect 不带 token → UNAUTHORIZED
        var noToken = sendReq(c3, "u2", "connect", null);
        assertFalse(noToken.path("ok").asBoolean());
        assertEquals("UNAUTHORIZED", noToken.path("error").path("code").asText());

        // 11. 设备配对流程
        var pairRes = sendReq(c1, "d1", "device.pair.request",
                "{\"label\":\"my-laptop\",\"publicKey\":\"pk123\",\"platform\":\"windows\"}");
        assertTrue(pairRes.path("ok").asBoolean());
        String pairingId = pairRes.path("payload").path("pairingId").asText();
        assertEquals("pending", pairRes.path("payload").path("status").asText());

        var approveRes = sendReq(c1, "d2", "device.pair.approve",
                "{\"pairingId\":\"" + pairingId + "\",\"label\":\"my-laptop\","
                + "\"publicKey\":\"pk123\",\"platform\":\"windows\",\"role\":\"developer\"}");
        assertTrue(approveRes.path("ok").asBoolean());
        assertEquals("approved", approveRes.path("payload").path("status").asText());

        var devList = sendReq(c1, "d3", "device.list", null);
        assertTrue(devList.path("ok").asBoolean());
        assertEquals(1, devList.path("payload").path("devices").size());
        assertEquals("my-laptop", devList.path("payload").path("devices").get(0).path("label").asText());

        // 12. auth.users.create → 新用户
        var createRes = sendReq(c1, "u3", "auth.users.create",
                "{\"username\":\"dev1\",\"displayName\":\"Dev One\",\"password\":\"devpass\",\"role\":\"developer\"}");
        assertTrue(createRes.path("ok").asBoolean());
        assertEquals("developer", createRes.path("payload").path("role").asText());

        // 13. 不能二次 bootstrap
        var bs2 = sendReq(c1, "b5", "auth.bootstrap",
                "{\"username\":\"admin2\",\"displayName\":\"Admin2\",\"password\":\"p\",\"role\":\"admin\"}");
        assertFalse(bs2.path("ok").asBoolean());
        assertEquals("CONFLICT", bs2.path("error").path("code").asText());

        // cleanup
        c1.session().close();
        c2.session().close();
        c3.session().close();
    }
}
