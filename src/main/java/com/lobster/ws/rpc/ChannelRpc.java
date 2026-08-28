package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.ChannelStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 频道接入 RPC（M5-23）。 */
@Component
public class ChannelRpc extends BaseRpc {

    private final ChannelStore channelStore;

    public ChannelRpc(ChannelStore channelStore) { this.channelStore = channelStore; }

    @Override
    public Set<String> methods() {
        return Set.of("channels.bindings.list", "channels.bindings.create", "channels.bindings.remove", "channels.status");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "channels.bindings.list" -> bindingsList(id);
            case "channels.bindings.create" -> bindingsCreate(id, params);
            case "channels.bindings.remove" -> bindingsRemove(id, params);
            case "channels.status" -> status(id);
        }
    }

    private void bindingsList(String id) {
        sendRes(id, true, on().set("bindings", (ArrayNode) ctx.om().valueToTree(channelStore.list())));
    }

    private void bindingsCreate(String id, JsonNode params) {
        String channel = params.path("channel").asText();
        String accountId = params.path("accountId").asText();
        String agentId = params.path("agentId").asText("main");
        String config = params.path("config").asText(null);
        if (channel.isEmpty() || accountId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "channel 和 accountId 必填"));
            return;
        }
        try {
            var binding = channelStore.create(channel, accountId, agentId, config);
            audit("channel.binding.create", null, "created",
                    on().put("bindingId", binding.id()).put("channel", channel).toString());
            sendRes(id, true, on().put("bindingId", binding.id()).put("channel", channel).put("accountId", accountId));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "CONFLICT").put("message", "绑定已存在"));
        }
    }

    private void bindingsRemove(String id, JsonNode params) {
        String bindingId = params.path("bindingId").asText();
        if (bindingId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "bindingId 必填"));
            return;
        }
        channelStore.remove(bindingId);
        sendRes(id, true, on().put("bindingId", bindingId));
    }

    private void status(String id) {
        ArrayNode status = arr();
        for (var b : channelStore.list()) {
            status.add(on().put("bindingId", b.id()).put("channel", b.channel())
                    .put("accountId", b.accountId()).put("agentId", b.agentId()).put("status", "active"));
        }
        sendRes(id, true, on().set("channels", status));
    }
}
