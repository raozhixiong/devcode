package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量 WS RPC E2E 测试：通过真实 WebSocket 驱动经重构后的 WsHandler 薄分发层，
 * 覆盖每一个领域 RpcHandler 的请求路由与执行，验证 method→handler 映射正确、
 * 且各受保护方法在鉴权后可达（注册表持久化于同一状态目录，故每个用例用 bootstrap 或 login 鉴权）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WsRpcE2ETest {

    @Autowired Environment env;
    private final ObjectMapper om = new ObjectMapper();
    private WsClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var dir = Path.of("target/test-state-e2e");
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

    private WsClient connect() throws Exception {
        var c = new StandardWebSocketClient();
        var received = new CopyOnWriteArrayList<JsonNode>();
        var f = new CompletableFuture<WebSocketSession>();
        c.execute(new TextWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession s) { f.complete(s); }
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                received.add(om.readTree(m.getPayload()));
            }
        }, "ws://localhost:" + env.getProperty("local.server.port", Integer.class) + "/ws").get(5, TimeUnit.SECONDS);
        return new WsClient(f.get(5, TimeUnit.SECONDS), received);
    }

    private JsonNode rpc(String id, String method, Object params) throws Exception {
        String frame = "{\"type\":\"req\",\"id\":\"" + id + "\",\"method\":\"" + method + "\""
                + (params != null ? ",\"params\":" + om.writeValueAsString(params) : "") + "}";
        client.session().sendMessage(new TextMessage(frame));
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            for (var n : client.received()) {
                if ("res".equals(n.path("type").asText()) && id.equals(n.path("id").asText()))
                    return n;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("Timeout waiting for res id=" + id);
    }

    /** 断言请求被正确路由到 handler 并执行（ok 或返回领域错误，而非 METHOD_NOT_FOUND）。 */
    private JsonNode assertRouted(String id, String method, Object params) throws Exception {
        var res = rpc(id, method, params);
        assertEquals("res", res.path("type").asText(), method + " 应返回 res");
        assertEquals(id, res.path("id").asText(), method + " res.id 应匹配");
        if (!res.path("ok").asBoolean(false)) {
            String code = res.path("error").path("code").asText();
            assertNotEquals("METHOD_NOT_FOUND", code, method + " 不应路由到未知方法");
        }
        return res;
    }

    private JsonNode assertOk(String id, String method, Object params) throws Exception {
        var res = rpc(id, method, params);
        assertTrue(res.path("ok").asBoolean(false),
                method + " 应成功, 实际: " + res.path("error").toPrettyString());
        return res;
    }

    @BeforeEach
    void setUp() throws Exception {
        client = connect();
        var bs = rpc("bootstrap", "auth.bootstrap",
                Map.of("username", "admin", "displayName", "Admin", "password", "pass123", "role", "admin"));
        if (!bs.path("ok").asBoolean(false)) {
            // 状态目录在用例间持久化，bootstrap 已初始化 → 用 login 鉴权当前会话
            var login = rpc("bootlogin", "auth.login", Map.of("username", "admin", "password", "pass123"));
            assertTrue(login.path("ok").asBoolean(false), "auth.login 应成功: " + login.path("error").toPrettyString());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.session().close();
    }

    @Test
    void sessionAndChatE2E() throws Exception {
        assertOk("c1", "chat.send", Map.of("sessionKey", "e2e-main", "text", "hi"));
        assertOk("c2", "chat.history", Map.of("sessionKey", "e2e-main"));
        assertOk("s0", "sessions.list", null);
        assertOk("s1", "mode.set", Map.of("sessionKey", "e2e-main", "mode", "plan"));
        assertOk("s2", "queue.mode.set", Map.of("sessionKey", "e2e-main", "mode", "steer"));
        assertOk("s3", "sessions.rename", Map.of("sessionKey", "e2e-main", "title", "Renamed"));
        assertOk("s4", "sessions.fork", Map.of("sessionKey", "e2e-main"));
        assertRouted("s5", "sessions.rewind", Map.of("sessionKey", "e2e-main", "upToMessageId", ""));
        assertOk("s6", "sessions.assignOwner", Map.of("sessionKey", "e2e-main", "owner", "u1"));
        assertOk("s7", "sessions.participants", Map.of("sessionKey", "e2e-main"));
        assertOk("s8", "sessions.state", Map.of("sessionKey", "e2e-main"));
        assertOk("s9", "sessions.changesSince", Map.of("sessionKey", "e2e-main", "since", 0));
        assertOk("s10", "permission.respond", Map.of("requestId", "x", "decision", "approve"));
        assertOk("s11", "sessions.archive", Map.of("sessionKey", "e2e-main"));
    }

    @Test
    void tasksE2E() throws Exception {
        assertOk("t1", "tasks.list", null);
        assertRouted("t2", "tasks.get", Map.of("id", "nope"));
        assertRouted("t3", "tasks.cancel", Map.of("id", "nope"));
    }

    @Test
    void workboardE2E() throws Exception {
        assertOk("w0", "workboard.cards.list", null);
        var add = assertOk("w1", "workboard.cards.create",
                Map.of("title", "卡片1", "status", "triage", "priority", "normal", "labels", "x"));
        String cardId = add.path("payload").path("id").asText();
        assertOk("w2", "workboard.cards.move", Map.of("cardId", cardId, "status", "TODO"));
        assertOk("w3", "workboard.cards.update", Map.of("cardId", cardId, "title", "改"));
        assertOk("w4", "workboard.cards.events", Map.of("cardId", cardId));
        assertOk("w5", "workboard.cards.delete", Map.of("cardId", cardId));
    }

    @Test
    void cronE2E() throws Exception {
        var ag = assertOk("ca", "agents.create", Map.of("name", "cron-agent", "role", "developer"));
        String agentId = ag.path("payload").path("id").asText();
        assertOk("cr0", "cron.list", null);
        var add = assertOk("cr1", "cron.add",
                Map.of("agentId", agentId, "name", "heart", "schedule", "0 * * * * ?", "prompt", "hi"));
        String jobId = add.path("payload").path("id").asText();
        assertOk("cr2", "cron.get", Map.of("jobId", jobId));
        assertOk("cr3", "cron.update", Map.of("jobId", jobId, "name", "heart2"));
        assertOk("cr4", "cron.run", Map.of("jobId", jobId));
        assertOk("cr5", "cron.runs", Map.of("jobId", jobId));
        assertOk("cr6", "cron.remove", Map.of("jobId", jobId));
    }

    @Test
    void memoryE2E() throws Exception {
        assertOk("m1", "memory.search", Map.of("query", "lobster"));
        assertOk("m2", "memory.recent", Map.of("days", 2));
        assertOk("m3", "memory.curated", null);
        assertOk("m4", "dreaming.sweep", null);
    }

    @Test
    void usageE2E() throws Exception {
        assertOk("u1", "usage.byAgent", null);
        assertRouted("u2", "usage.session", Map.of("sessionKey", "e2e-main"));
        assertOk("u3", "usage.daily", Map.of("days", 7));
        assertOk("u4", "usage.sessions", null);
    }

    @Test
    void skillsE2E() throws Exception {
        assertOk("sk1", "skills.list", null);
        assertOk("sk2", "skills.install", Map.of("name", "e2e-skill", "content", "echo hi"));
        assertOk("sk3", "skills.setEnabled", Map.of("name", "e2e-skill", "enabled", false));
        assertRouted("sk4", "skills.get", Map.of("name", "e2e-skill"));
    }

    @Test
    void agentsE2E() throws Exception {
        assertOk("a1", "agents.list", null);
        assertOk("a2", "agents.create", Map.of("name", "e2e-agent", "role", "developer"));
    }

    @Test
    void authE2E() throws Exception {
        assertOk("au1", "auth.users.list", null);
        assertOk("au2", "auth.users.create",
                Map.of("username", "dev1", "displayName", "Dev", "password", "devpass", "role", "developer"));
        assertRouted("au3", "auth.token.revoke", Map.of("tokenId", "x"));
    }

    @Test
    void deviceE2E() throws Exception {
        var req = assertOk("d1", "device.pair.request",
                Map.of("label", "laptop", "publicKey", "pk", "platform", "windows", "scopes", "read"));
        String pid = req.path("payload").path("pairingId").asText();
        var appr = assertOk("d2", "device.pair.approve",
                Map.of("pairingId", pid, "label", "laptop", "publicKey", "pk", "platform", "windows", "role", "developer"));
        String deviceId = appr.path("payload").path("deviceId").asText();
        assertOk("d3", "device.pair.status", null);
        assertOk("d4", "device.list", null);
        assertOk("d5", "device.rename", Map.of("deviceId", deviceId, "label", "renamed"));
        assertOk("d6", "device.revoke", Map.of("deviceId", deviceId));
    }

    @Test
    void auditE2E() throws Exception {
        assertOk("ad1", "audit.activity.list", null);
        assertOk("ad2", "audit.run.inspect", Map.of("sessionKey", "e2e-main"));
    }

    @Test
    void approvalE2E() throws Exception {
        assertOk("ap0", "approval.list", null);
        var r = assertOk("ap1", "approval.request",
                Map.of("kind", "exec", "sessionKey", "e2e-main", "requester", "me", "payload", "{}"));
        String id = r.path("payload").path("approvalId").asText();
        assertOk("ap2", "approval.get", Map.of("id", id));
        assertOk("ap3", "approval.resolve", Map.of("id", id, "decision", "approve"));
        assertOk("ap4", "approval.history", Map.of("kind", "exec"));
        assertOk("ap5", "exec.approvals.get", Map.of("scope", "gateway"));
        assertOk("ap6", "exec.approvals.set", Map.of("scope", "gateway", "policy", "{}"));
    }

    @Test
    void channelE2E() throws Exception {
        var ag = assertOk("chag", "agents.create", Map.of("name", "channel-agent", "role", "developer"));
        String agentId = ag.path("payload").path("id").asText();
        assertOk("ch0", "channels.bindings.list", null);
        String accountId = "acc-" + System.nanoTime();
        var add = assertOk("ch1", "channels.bindings.create",
                Map.of("channel", "webhook", "accountId", accountId, "agentId", agentId, "config", "{}"));
        String bindingId = add.path("payload").path("bindingId").asText();
        assertOk("ch2", "channels.bindings.remove", Map.of("bindingId", bindingId));
        assertOk("ch3", "channels.status", null);
    }

    @Test
    void configE2E() throws Exception {
        assertOk("cf1", "config.set", Map.of("path", "app/theme", "value", "dark"));
        assertOk("cf2", "config.get", Map.of("path", "app/theme"));
        assertRouted("cf3", "config.patch", Map.of("path", "app/theme", "patches", "{\"value\":\"light\"}"));
        assertOk("cf4", "config.list", null);
    }

    @Test
    void pluginE2E() throws Exception {
        assertOk("pl1", "plugins.list", null);
        assertOk("pl2", "plugins.marketplace", null);
        var install = assertOk("pl3", "plugins.install", Map.of("name", "e2e-plugin", "source", "builtin:e2e-plugin"));
        String id = install.path("payload").path("pluginId").asText();
        assertOk("pl4", "plugins.setEnabled", Map.of("id", id, "enabled", false));
        assertOk("pl5", "plugins.uninstall", Map.of("id", id));
    }

    @Test
    void hookE2E() throws Exception {
        var install = assertOk("h1", "hooks.install", Map.of("event", "session.created", "command", "echo hi"));
        String id = install.path("payload").path("hookId").asText();
        assertOk("h2", "hooks.list", null);
        assertOk("h3", "hooks.setEnabled", Map.of("id", id, "enabled", true));
        assertOk("h4", "hooks.remove", Map.of("id", id));
    }

    @Test
    void commandE2E() throws Exception {
        assertOk("cm1", "command.list", null);
        assertRouted("cm2", "command.run", Map.of("slash", "/compact", "sessionId", "e2e-main"));
    }

    @Test
    void integrationE2E() throws Exception {
        assertOk("i1", "integration.list", null);
        var install = assertOk("i2", "integration.install", Map.of("name", "webhook", "kind", "webhook"));
        String id = install.path("payload").path("integrationId").asText();
        assertOk("i3", "integration.connect.key", Map.of("id", id, "key", "secret-key"));
        assertRouted("i4", "integration.connect.oauth", Map.of("id", id));
        assertRouted("i5", "integration.attempt.status", Map.of("attemptId", "x"));
        assertRouted("i6", "integration.attempt.complete", Map.of("attemptId", "x", "config", "{}"));
        assertRouted("i7", "integration.attempt.cancel", Map.of("attemptId", "x"));
    }

    @Test
    void referenceE2E() throws Exception {
        assertOk("rf1", "reference.list", null);
        var install = assertOk("rf2", "reference.install",
                Map.of("name", "ref1", "kind", "local", "uri", "file:///tmp/e2e", "description", "d"));
        String id = install.path("payload").path("referenceId").asText();
        assertOk("rf3", "reference.setEnabled", Map.of("id", id, "enabled", false));
        assertRouted("rf4", "reference.read", Map.of("id", id));
        assertOk("rf5", "reference.remove", Map.of("id", id));
    }

    @Test
    void artifactE2E() throws Exception {
        assertRouted("ar1", "artifact.list", Map.of("sessionId", "e2e-main"));
        assertRouted("ar2", "artifact.attach",
                Map.of("sessionId", "e2e-main", "agentId", "main", "name", "图", "kind", "image"));
        assertRouted("ar3", "artifact.remove", Map.of("id", "x"));
    }

    @Test
    void shareE2E() throws Exception {
        assertOk("sh1", "chat.send", Map.of("sessionKey", "e2e-main", "text", "hi"));
        var create = assertOk("sh2", "share.create", Map.of("sessionKey", "e2e-main"));
        String token = create.path("payload").path("token").asText();
        assertOk("sh3", "share.open", Map.of("token", token));
    }

    @Test
    void worktreeE2E() throws Exception {
        assertRouted("wt1", "worktree.create", Map.of("agentId", "main"));
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        var res = rpc("unk", "totally.unknown.method", null);
        assertFalse(res.path("ok").asBoolean(false));
        assertEquals("METHOD_NOT_FOUND", res.path("error").path("code").asText());
    }
}
