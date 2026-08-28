package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.IntegrationStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 集成 / OAuth RPC（M6-22a / FR-I4）。 */
@Component
public class IntegrationRpc extends BaseRpc {

    private final IntegrationStore integrationStore;

    public IntegrationRpc(IntegrationStore integrationStore) { this.integrationStore = integrationStore; }

    @Override
    public Set<String> methods() {
        return Set.of("integration.list", "integration.install", "integration.connect.key",
                "integration.connect.oauth", "integration.attempt.status",
                "integration.attempt.complete", "integration.attempt.cancel");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "integration.list" -> list(id);
            case "integration.install" -> install(id, params);
            case "integration.connect.key" -> connectKey(id, params);
            case "integration.connect.oauth" -> connectOauth(id, params);
            case "integration.attempt.status" -> attemptStatus(id, params);
            case "integration.attempt.complete" -> attemptComplete(id, params);
            case "integration.attempt.cancel" -> attemptCancel(id, params);
        }
    }

    private void list(String id) {
        sendRes(id, true, on().set("integrations", (ArrayNode) ctx.om().valueToTree(integrationStore.list())));
    }

    private void install(String id, JsonNode params) {
        String name = params.path("name").asText();
        String kind = params.path("kind").asText();
        String key = params.path("key").asText();
        if (name.isEmpty() || kind.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "name 和 kind 必填"));
            return;
        }
        var it = integrationStore.install(name, kind);
        if (!key.isEmpty()) integrationStore.connectKey(it.id(), key);
        sendRes(id, true, on().put("integrationId", it.id()).put("name", it.name()).put("status", it.status()));
    }

    private void connectKey(String id, JsonNode params) {
        String integrationId = params.path("id").asText();
        String key = params.path("key").asText();
        if (integrationId.isEmpty() || key.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 和 key 必填"));
            return;
        }
        integrationStore.connectKey(integrationId, key);
        sendRes(id, true, on().put("integrationId", integrationId));
    }

    private void connectOauth(String id, JsonNode params) {
        String integrationId = params.path("id").asText();
        if (integrationId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        var attempt = integrationStore.startOAuth(integrationId);
        sendRes(id, true, on().put("integrationId", integrationId).put("attemptId", attempt.id()));
    }

    private void attemptStatus(String id, JsonNode params) {
        String attemptId = params.path("attemptId").asText();
        var a = integrationStore.getAttempt(attemptId);
        if (a == null) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "attempt 不存在"));
            return;
        }
        sendRes(id, true, on().put("attemptId", a.id()).put("status", a.status()).put("step", a.step()));
    }

    private void attemptComplete(String id, JsonNode params) {
        String attemptId = params.path("attemptId").asText();
        if (attemptId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "attemptId 必填"));
            return;
        }
        integrationStore.completeAttempt(attemptId, params.path("config").toString());
        sendRes(id, true, on().put("attemptId", attemptId));
    }

    private void attemptCancel(String id, JsonNode params) {
        String attemptId = params.path("attemptId").asText();
        if (attemptId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "attemptId 必填"));
            return;
        }
        integrationStore.cancelAttempt(attemptId);
        sendRes(id, true, on().put("attemptId", attemptId));
    }
}
