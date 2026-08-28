package com.lobster.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.store.ChannelStore;
import com.lobster.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** 频道出站回放（对齐 FR-B3-4）：agent 在频道会话里产出回答后，回发到平台。 */
@Component
public class ChannelReplyService {

    private static final Logger log = LoggerFactory.getLogger(ChannelReplyService.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private final ChannelStore channelStore;
    private final MessageStore messageStore;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ChannelReplyService(ChannelStore channelStore, MessageStore messageStore, EventBus bus) {
        this.channelStore = channelStore;
        this.messageStore = messageStore;
        bus.subscribeAll(this::onEvent);
    }

    private void onEvent(LobsterEvent e) {
        if (!Events.SESSION_IDLE.equals(e.type())) return;
        var sess = messageStore.findById(e.aggregateId());
        if (sess.isEmpty()) return;
        String key = sess.get().sessionKey();
        if (!key.startsWith("channel:")) return;
        String[] parts = key.split(":", 4);
        if (parts.length < 3) return;
        String channel = parts[1], accountId = parts[2];
        Optional<ChannelStore.ChannelBinding> binding = channelStore.get(channel, accountId);
        if (binding.isEmpty()) return;
        String outbound = parseOutbound(binding.get().config());
        if (outbound.isEmpty()) return;
        Optional<Message> last = messageStore.lastMessage(e.aggregateId());
        if (last.isEmpty()) return;
        String text = last.get().parts().stream()
                .filter(p -> p instanceof Part.Text)
                .map(p -> ((Part.Text) p).text())
                .reduce("", (a, b) -> a + b);
        if (text.isBlank()) return;
        try {
            post(channel, outbound, text);
        } catch (Exception ex) {
            log.warn("频道出站回放失败 {}/{}: {}", channel, accountId, ex.getMessage());
        }
    }

    private String parseOutbound(String config) {
        try {
            return OM.readTree(config).path("outboundUrl").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private void post(String channel, String url, String text) throws Exception {
        ObjectNode body = switch (channel) {
            case "wecom" -> OM.createObjectNode().put("msgtype", "text")
                    .set("text", OM.createObjectNode().put("content", text));
            case "dingtalk" -> OM.createObjectNode().put("msgtype", "text")
                    .set("text", OM.createObjectNode().put("content", text));
            case "feishu" -> OM.createObjectNode().put("msg_type", "text")
                    .set("content", OM.createObjectNode().put("text", text));
            default -> OM.createObjectNode().put("text", text);
        };
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + r.statusCode());
        }
    }
}
