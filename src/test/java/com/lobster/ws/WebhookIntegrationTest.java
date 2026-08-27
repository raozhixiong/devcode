package com.lobster.ws;

import com.lobster.rbac.AgentRegistry;
import com.lobster.store.ChannelStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 频道入站集成测试：POST /hooks/{channel}/{accountId} -> AgentLoop。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookIntegrationTest {

    @Autowired Environment env;
    @Autowired ChannelStore channelStore;
    @Autowired AgentRegistry agentRegistry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var dir = Path.of("target/test-state-webhook");
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

    @Test
    void webhookNotFoundWithoutBinding() {
        int port = env.getProperty("local.server.port", Integer.class);
        var rest = RestClient.create();
        try {
            rest.post()
                    .uri("http://localhost:" + port + "/hooks/webhook/unknown")
                    .body("hello")
                    .retrieve()
                    .toEntity(Map.class);
            fail("Should have returned 404");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            assertTrue(e.getResponseBodyAsString().contains("NO_BINDING"));
        }
    }

    @Test
    void webhookRoutesMessageWithBinding() {
        int port = env.getProperty("local.server.port", Integer.class);
        // 创建 agent + 绑定
        var agent = agentRegistry.create("webhook-agent", "developer", null, null, null);
        channelStore.create("webhook", "acct-test", agent.id(), "{}");

        var rest = RestClient.create();
        var resp = rest.post()
                .uri("http://localhost:" + port + "/hooks/webhook/acct-test")
                .body("hello from webhook")
                .retrieve()
                .toEntity(Map.class);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("accepted", resp.getBody().get("status"));
        assertNotNull(resp.getBody().get("sessionKey"));
        assertTrue(resp.getBody().get("sessionKey").toString().contains("webhook"));
    }

    @Test
    void webhookWithCustomSender() {
        int port = env.getProperty("local.server.port", Integer.class);
        var agent = agentRegistry.create("sender-agent", "developer", null, null, null);
        channelStore.create("webhook", "acct-sender", agent.id(), "{}");

        var rest = RestClient.create();
        var resp = rest.post()
                .uri("http://localhost:" + port + "/hooks/webhook/acct-sender")
                .header("X-Lobster-Sender", "user-alice")
                .body("message from alice")
                .retrieve()
                .toEntity(Map.class);

        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().get("sessionKey").toString().contains("user-alice"));
    }
}
