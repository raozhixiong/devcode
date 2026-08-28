package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.HookStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 钩子 RPC（M6-29 / FR-I1）。 */
@Component
public class HookRpc extends BaseRpc {

    private final HookStore hookStore;

    public HookRpc(HookStore hookStore) { this.hookStore = hookStore; }

    @Override
    public Set<String> methods() {
        return Set.of("hooks.install", "hooks.list", "hooks.setEnabled", "hooks.remove");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "hooks.install" -> install(id, params);
            case "hooks.list" -> list(id);
            case "hooks.setEnabled" -> setEnabled(id, params);
            case "hooks.remove" -> remove(id, params);
        }
    }

    private void install(String id, JsonNode params) {
        String event = params.path("event").asText();
        String kind = params.path("kind").asText("command");
        String command = params.path("command").asText();
        String scope = params.path("scope").asText("global");
        String scopeId = params.path("scopeId").asText(null);
        int timeout = params.path("timeoutMs").asInt(5000);
        if (event.isEmpty() || command.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "event 和 command 必填"));
            return;
        }
        var hook = hookStore.install(scope, scopeId, event, kind, command, timeout);
        sendRes(id, true, on().put("hookId", hook.id()).put("event", hook.event()).put("scope", hook.scope()));
    }

    private void list(String id) {
        sendRes(id, true, on().set("hooks", (ArrayNode) ctx.om().valueToTree(hookStore.list())));
    }

    private void setEnabled(String id, JsonNode params) {
        String hookId = params.path("id").asText();
        boolean enabled = params.path("enabled").asBoolean(true);
        if (hookId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        hookStore.setEnabled(hookId, enabled);
        sendRes(id, true, on().put("hookId", hookId).put("enabled", enabled));
    }

    private void remove(String id, JsonNode params) {
        String hookId = params.path("id").asText();
        if (hookId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        hookStore.remove(hookId);
        sendRes(id, true, on().put("hookId", hookId));
    }
}
