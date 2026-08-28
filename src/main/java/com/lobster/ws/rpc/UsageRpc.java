package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.MessageStore;
import com.lobster.store.UsageStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** Usage 统计 RPC（M4-21）。 */
@Component
public class UsageRpc extends BaseRpc {

    private final UsageStore usage;
    private final MessageStore store;

    public UsageRpc(UsageStore usage, MessageStore store) {
        this.usage = usage;
        this.store = store;
    }

    @Override
    public Set<String> methods() {
        return Set.of("usage.byAgent", "usage.session", "usage.daily", "usage.sessions");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "usage.byAgent" -> byAgent(id);
            case "usage.session" -> bySession(id, params);
            case "usage.daily" -> daily(id, params);
            case "usage.sessions" -> sessions(id);
        }
    }

    private void byAgent(String id) {
        ArrayNode arr = arr();
        for (var u : usage.usageByAgent()) {
            arr.add(on().put("agentId", u.agentId()).put("totalInput", u.totalInput())
                    .put("totalOutput", u.totalOutput()).put("totalCost", u.totalCost())
                    .put("sessionCount", u.sessionCount()));
        }
        sendRes(id, true, on().set("agents", arr));
    }

    private void bySession(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        var u = usage.sessionUsage(s.id());
        if (u == null) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        sendRes(id, true, on().put("sessionId", u.sessionId()).put("tokensInput", u.tokensInput())
                .put("tokensOutput", u.tokensOutput()).put("cost", u.cost()));
    }

    private void daily(String id, JsonNode params) {
        ArrayNode arr = arr();
        for (var d : usage.dailyUsage(params.path("days").asInt(30))) {
            arr.add(on().put("date", d.date()).put("totalInput", d.totalInput())
                    .put("totalOutput", d.totalOutput()).put("totalCost", d.totalCost())
                    .put("sessionCount", d.sessionCount()));
        }
        sendRes(id, true, on().set("daily", arr));
    }

    private void sessions(String id) {
        ArrayNode arr = arr();
        for (var s : usage.listSessions()) {
            arr.add(on().put("sessionId", s.sessionId()).put("sessionKey", s.sessionKey())
                    .put("agentId", s.agentId()).put("tokensInput", s.tokensInput())
                    .put("tokensOutput", s.tokensOutput()).put("cost", s.cost())
                    .put("createdAt", s.createdAt()).put("updatedAt", s.updatedAt()));
        }
        sendRes(id, true, on().set("sessions", arr));
    }
}
