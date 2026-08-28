package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.rbac.AgentRegistry;
import com.lobster.rbac.Role;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 角色/Agent 实例 RPC（M3-12）。 */
@Component
public class AgentRpc extends BaseRpc {

    private final AgentRegistry agents;

    public AgentRpc(AgentRegistry agents) { this.agents = agents; }

    @Override
    public Set<String> methods() { return Set.of("agents.list", "agents.create"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        if ("agents.list".equals(method)) list(id);
        else if ("agents.create".equals(method)) create(id, params);
    }

    private void list(String id) {
        ArrayNode arr = arr();
        for (var a : agents.list()) {
            ObjectNode n = on().put("id", a.id()).put("name", a.name()).put("role", a.role())
                    .put("emoji", a.emoji()).put("modelId", a.modelId()).put("workspaceDir", a.workspaceDir());
            n.set("allowedTools", ctx.om().valueToTree(Role.of(a.role()).allowedTools()));
            arr.add(n);
        }
        sendRes(id, true, on().set("agents", arr));
    }

    private void create(String id, JsonNode params) {
        String name = params.path("name").asText();
        String role = params.path("role").asText();
        if (name.isEmpty() || role.isEmpty()) {
            sendRes(id, false, on().put("code", "INVALID_PARAMS").put("message", "name 与 role 必填"));
            return;
        }
        try {
            var a = agents.create(name, role, params.path("emoji").asText(null),
                    params.path("modelProvider").asText(null), params.path("modelId").asText(null));
            ObjectNode payload = on().put("id", a.id()).put("name", a.name()).put("role", a.role()).put("emoji", a.emoji());
            payload.set("allowedTools", ctx.om().valueToTree(Role.of(a.role()).allowedTools()));
            sendRes(id, true, payload);
        } catch (IllegalArgumentException e) {
            sendRes(id, false, on().put("code", "INVALID_ROLE").put("message", String.valueOf(e.getMessage())));
        }
    }
}
