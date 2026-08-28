package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.AuditStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 审计台账 RPC（M5-26）。 */
@Component
public class AuditRpc extends BaseRpc {

    private final AuditStore auditStore;

    public AuditRpc(AuditStore auditStore) { this.auditStore = auditStore; }

    @Override
    public Set<String> methods() { return Set.of("audit.activity.list", "audit.run.inspect"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        if ("audit.activity.list".equals(method)) activityList(id, params);
        else if ("audit.run.inspect".equals(method)) runInspect(id, params);
    }

    private void activityList(String id, JsonNode params) {
        String kindFilter = params.path("kind").asText(null);
        int limit = params.path("limit").asInt(50);
        Long beforeTs = params.path("beforeTs").asLong(0);
        if (beforeTs == 0) beforeTs = null;
        var events = auditStore.list(kindFilter, limit, beforeTs);
        sendRes(id, true, on().set("events", (ArrayNode) ctx.om().valueToTree(events)));
    }

    private void runInspect(String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText(null);
        if (sessionKey == null || sessionKey.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "sessionKey 必填"));
            return;
        }
        var events = auditStore.listBySession(sessionKey, 200);
        sendRes(id, true, on().set("events", (ArrayNode) ctx.om().valueToTree(events)));
    }
}
