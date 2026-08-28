package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.ArtifactsStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

/** Artifacts RPC（M6-31 / FR-I6）。 */
@Component
public class ArtifactRpc extends BaseRpc {

    private final ArtifactsStore artifactsStore;

    public ArtifactRpc(ArtifactsStore artifactsStore) { this.artifactsStore = artifactsStore; }

    @Override
    public Set<String> methods() { return Set.of("artifact.list", "artifact.attach", "artifact.remove"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "artifact.list" -> list(id, params);
            case "artifact.attach" -> attach(id, params);
            case "artifact.remove" -> remove(id, params);
        }
    }

    private void list(String id, JsonNode params) {
        String sessionId = params.path("sessionId").asText();
        var list = sessionId.isEmpty() ? List.of() : artifactsStore.listBySession(sessionId);
        sendRes(id, true, on().set("artifacts", (ArrayNode) ctx.om().valueToTree(list)));
    }

    private void attach(String id, JsonNode params) {
        String sessionId = params.path("sessionId").asText();
        String agentId = params.path("agentId").asText();
        String kind = params.path("kind").asText("generated");
        String name = params.path("name").asText();
        String path = params.path("path").asText("");
        String mime = params.path("mime").asText("");
        if (name.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "name 必填"));
            return;
        }
        var a = artifactsStore.attach(sessionId, agentId, kind, name, path, mime);
        sendRes(id, true, on().put("artifactId", a.id()).put("name", a.name()));
    }

    private void remove(String id, JsonNode params) {
        String artId = params.path("id").asText();
        if (artId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        artifactsStore.remove(artId);
        sendRes(id, true, on().put("id", artId));
    }
}
