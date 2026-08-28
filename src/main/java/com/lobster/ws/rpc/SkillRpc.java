package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.SkillsStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 技能 RPC（M4-22）。 */
@Component
public class SkillRpc extends BaseRpc {

    private final SkillsStore skills;

    public SkillRpc(SkillsStore skills) { this.skills = skills; }

    @Override
    public Set<String> methods() {
        return Set.of("skills.list", "skills.get", "skills.setEnabled", "skills.install");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "skills.list" -> list(id);
            case "skills.get" -> get(id, params);
            case "skills.setEnabled" -> setEnabled(id, params);
            case "skills.install" -> install(id, params);
        }
    }

    private void list(String id) {
        ArrayNode arr = arr();
        for (var s : skills.list()) {
            arr.add(on().put("name", s.name()).put("description", s.description()).put("enabled", s.enabled()));
        }
        sendRes(id, true, on().set("skills", arr));
    }

    private void get(String id, JsonNode params) {
        var s = skills.get(params.path("name").asText());
        if (s.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        sendRes(id, true, on().put("name", s.get().name()).put("description", s.get().description())
                .put("content", s.get().content()).put("enabled", s.get().enabled()));
    }

    private void setEnabled(String id, JsonNode params) {
        boolean ok = skills.setEnabled(params.path("name").asText(), params.path("enabled").asBoolean());
        sendRes(id, ok, ok ? on().put("updated", true) : on().put("code", "NOT_FOUND"));
    }

    private void install(String id, JsonNode params) {
        var s = skills.install(params.path("name").asText(), params.path("content").asText());
        sendRes(id, true, on().put("name", s.name()).put("installed", true));
    }
}
