package com.lobster.ws;

import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Part;
import com.lobster.store.ChannelStore;
import com.lobster.store.MessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 频道入站（对齐 FR-B3-1/B3-4）：POST /hooks/{channel}/{accountId} -> AgentLoop。 */
@RestController
public class WebhookController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ChannelStore channelStore;
    private final MessageStore messageStore;
    private final AgentLoop loop;
    private final EventBus bus;

    public WebhookController(ChannelStore channelStore, MessageStore messageStore,
                             AgentLoop loop, EventBus bus) {
        this.channelStore = channelStore;
        this.messageStore = messageStore;
        this.loop = loop;
        this.bus = bus;
    }

    public record InboundResult(String status, String sessionKey, String sessionId) {}

    @PostMapping("/hooks/{channel}/{accountId}")
    public ResponseEntity<?> handleWebhook(
            @PathVariable String channel,
            @PathVariable String accountId,
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers) {
        // 1. 查找绑定
        var binding = channelStore.get(channel, accountId);
        if (binding.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "NO_BINDING",
                    "message", "频道 " + channel + "/" + accountId + " 未绑定"));
        }

        // 2. 提取消息和发送者
        String message = extractMessage(channel, body, headers);
        String sender = headers.getOrDefault("x-lobster-sender", "webhook");

        // 3. 路由到 agent 会话
        String sessionKey = "channel:" + channel + ":" + accountId + ":" + sender;
        var session = messageStore.findByKey(sessionKey)
                .orElseGet(() -> messageStore.createSession(sessionKey, "main",
                        System.getProperty("user.dir")));

        // 4. 追加用户消息 + 触发 loop
        if (loop.isBusy(session.id())) {
            // busy 时入队（inbox 处理）
            bus.publish(new LobsterEvent("session.input.queued", session.id(),
                    OM.createObjectNode().put("text", message)
                            .put("source", "channel:" + channel), false));
        } else {
            messageStore.appendUser(session.id(), List.of(new Part.Text(message, false, false)));
            bus.publish(new LobsterEvent(Events.PROMPT_ADMITTED, session.id(),
                    OM.createObjectNode()
                            .put("text", message)
                            .put("source", "channel:" + channel), true));
            Thread.ofVirtual().name("channel-" + session.id()).start(() -> loop.run(session.id()));
        }

        // 5. 返回结果
        ObjectNode result = OM.createObjectNode()
                .put("status", "accepted")
                .put("sessionKey", sessionKey)
                .put("sessionId", session.id());
        return ResponseEntity.ok(result);
    }

    /** 频道适配：提取消息文本。 */
    private String extractMessage(String channel, String body, Map<String, String> headers) {
        if (body == null || body.isBlank()) return "(empty)";
        return switch (channel) {
            case "webhook" -> body;
            case "wecom" -> extractJsonField(body, "Content", body);
            case "dingtalk" -> {
                try {
                    var node = OM.readTree(body);
                    var text = node.path("text").path("content").asText("");
                    if (text.isEmpty()) text = node.path("content").asText("");
                    yield text.isEmpty() ? body : text;
                } catch (Exception e) { yield body; }
            }
            case "feishu" -> {
                try {
                    var node = OM.readTree(body);
                    var content = node.path("event").path("message").path("content").asText("");
                    if (content.isEmpty()) content = node.path("text").asText("");
                    yield content.isEmpty() ? body : content;
                } catch (Exception e) { yield body; }
            }
            default -> body;
        };
    }

    private String extractJsonField(String json, String field, String fallback) {
        try {
            var node = OM.readTree(json);
            String val = node.path(field).asText("");
            return val.isEmpty() ? fallback : val;
        } catch (Exception e) {
            return fallback;
        }
    }
}
