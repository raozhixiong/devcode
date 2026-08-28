package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.ReferenceStore;
import com.lobster.tool.ToolContext;
import com.lobster.tool.builtin.ReferenceTool;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 参考库 RPC（M6-22b / FR-I3）。 */
@Component
public class ReferenceRpc extends BaseRpc {

    private final ReferenceStore referenceStore;
    private final ReferenceTool referenceTool;

    public ReferenceRpc(ReferenceStore referenceStore, ReferenceTool referenceTool) {
        this.referenceStore = referenceStore;
        this.referenceTool = referenceTool;
    }

    @Override
    public Set<String> methods() {
        return Set.of("reference.list", "reference.install", "reference.setEnabled", "reference.remove", "reference.read");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "reference.list" -> list(id);
            case "reference.install" -> install(id, params);
            case "reference.setEnabled" -> setEnabled(id, params);
            case "reference.remove" -> remove(id, params);
            case "reference.read" -> read(id, params);
        }
    }

    private void list(String id) {
        sendRes(id, true, on().set("references", (ArrayNode) ctx.om().valueToTree(referenceStore.list())));
    }

    private void install(String id, JsonNode params) {
        String name = params.path("name").asText();
        String kind = params.path("kind").asText("local");
        String uri = params.path("uri").asText();
        String desc = params.path("description").asText("");
        if (name.isEmpty() || uri.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "name 和 uri 必填"));
            return;
        }
        var ref = referenceStore.install(name, kind, uri, desc);
        sendRes(id, true, on().put("referenceId", ref.id()).put("name", ref.name()));
    }

    private void setEnabled(String id, JsonNode params) {
        String refId = params.path("id").asText();
        boolean enabled = params.path("enabled").asBoolean(true);
        if (refId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        referenceStore.setEnabled(refId, enabled);
        sendRes(id, true, on().put("id", refId).put("enabled", enabled));
    }

    private void remove(String id, JsonNode params) {
        String refId = params.path("id").asText();
        if (refId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        referenceStore.remove(refId);
        sendRes(id, true, on().put("id", refId));
    }

    private void read(String id, JsonNode params) {
        String name = params.path("name").asText();
        String refId = params.path("id").asText();
        var ref = refId.isEmpty() ? referenceStore.getByName(name) : referenceStore.get(refId);
        if (ref == null) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "参考库不存在"));
            return;
        }
        try {
            var content = referenceTool.execute(on().put("name", ref.name()), ToolContext.dummy()).output();
            ObjectNode res = on().put("id", ref.id()).put("name", ref.name()).put("content", content);
            sendRes(id, true, res);
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "READ_ERROR").put("message", e.getMessage()));
        }
    }
}
