package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.ApprovalStore;
import com.lobster.ws.ConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 审批中心 RPC（M5-24）。 */
@Component
public class ApprovalRpc extends BaseRpc {

    private final ApprovalStore approvalStore;
    private final ConnectionRegistry conn;

    public ApprovalRpc(ApprovalStore approvalStore, ConnectionRegistry conn) {
        this.approvalStore = approvalStore;
        this.conn = conn;
    }

    @Override
    public Set<String> methods() {
        return Set.of("approval.request", "approval.get", "approval.list", "approval.resolve",
                "approval.history", "exec.approvals.get", "exec.approvals.set");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "approval.request" -> request(id, params);
            case "approval.get" -> get(id, params);
            case "approval.list" -> list(id, params);
            case "approval.resolve" -> resolve(id, params);
            case "approval.history" -> history(id, params);
            case "exec.approvals.get" -> execGet(id, params);
            case "exec.approvals.set" -> execSet(id, params);
        }
    }

    private String getActor() {
        var auth = conn.getAuth(session.getId());
        return auth == null ? null : auth.username();
    }

    private void request(String id, JsonNode params) {
        var approval = approvalStore.create(params.path("kind").asText("exec"),
                params.path("sessionKey").asText(null), "main",
                params.path("requester").asText("anonymous"), params.path("payload").asText("{}"));
        audit("approval.request", params.path("sessionKey").asText(null), "created",
                on().put("approvalId", approval.id()).toString());
        sendRes(id, true, on().put("approvalId", approval.id()).put("status", approval.status()));
    }

    private void get(String id, JsonNode params) {
        String approvalId = params.path("id").asText();
        if (approvalId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        var approval = approvalStore.get(approvalId);
        if (approval.isEmpty()) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "审批不存在"));
            return;
        }
        sendRes(id, true, (ObjectNode) ctx.om().valueToTree(approval.get()));
    }

    private void list(String id, JsonNode params) {
        var approvals = approvalStore.list(params.path("kind").asText(null), params.path("status").asText(null));
        sendRes(id, true, on().set("approvals", (ArrayNode) ctx.om().valueToTree(approvals)));
    }

    private void resolve(String id, JsonNode params) {
        String approvalId = params.path("id").asText();
        String decision = params.path("decision").asText("approve");
        String reason = params.path("reason").asText(null);
        if (approvalId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        boolean approved = "approve".equals(decision);
        var result = approvalStore.resolve(approvalId, getActor(), approved, reason);
        if (result.isEmpty()) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "审批不存在或已处理"));
            return;
        }
        audit("approval.resolve", null, approved ? "approved" : "rejected",
                on().put("approvalId", approvalId).toString());
        sendRes(id, true, on().put("approvalId", approvalId).put("status", approved ? "approved" : "rejected"));
    }

    private void history(String id, JsonNode params) {
        Long beforeTs = params.path("beforeTs").asLong(0);
        if (beforeTs == 0) beforeTs = null;
        var h = approvalStore.history(params.path("kind").asText(null), beforeTs, params.path("limit").asInt(50));
        sendRes(id, true, on().set("history", (ArrayNode) ctx.om().valueToTree(h)));
    }

    private void execGet(String id, JsonNode params) {
        String scope = params.path("scope").asText("gateway");
        String policy = approvalStore.getPolicy(scope);
        ObjectNode payload = on().put("scope", scope);
        if (policy != null) payload.put("policy", policy);
        sendRes(id, true, payload);
    }

    private void execSet(String id, JsonNode params) {
        String scope = params.path("scope").asText("gateway");
        String policy = params.path("policy").asText("{}");
        approvalStore.setPolicy(scope, policy, getActor());
        audit("config.write", null, "exec_policy_set", on().put("scope", scope).toString());
        sendRes(id, true, on().put("scope", scope).put("updated", true));
    }
}
