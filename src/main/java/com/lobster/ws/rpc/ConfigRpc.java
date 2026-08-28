package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.ConfigStore;
import com.lobster.ws.ConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 配置中心 RPC（M5-28 / FR-I5）。 */
@Component
public class ConfigRpc extends BaseRpc {

    private final ConfigStore configStore;
    private final ConnectionRegistry conn;

    public ConfigRpc(ConfigStore configStore, ConnectionRegistry conn) {
        this.configStore = configStore;
        this.conn = conn;
    }

    @Override
    public Set<String> methods() { return Set.of("config.get", "config.set", "config.patch", "config.list"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "config.get" -> get(id, params);
            case "config.set" -> set(id, params);
            case "config.patch" -> patch(id, params);
            case "config.list" -> list(id);
        }
    }

    private String getActor() {
        var auth = conn.getAuth(session.getId());
        return auth == null ? null : auth.username();
    }

    private void get(String id, JsonNode params) {
        String path = params.path("path").asText();
        if (path.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "path 必填"));
            return;
        }
        var entry = configStore.get(path);
        if (entry.isEmpty()) {
            sendRes(id, true, on().put("path", path).put("found", false));
            return;
        }
        sendRes(id, true, on().put("path", path).put("found", true).put("value", entry.get().value())
                .put("revisionHash", entry.get().revisionHash()).put("reloadKind", configStore.reloadKind(path)));
    }

    private void set(String id, JsonNode params) {
        String path = params.path("path").asText();
        if (path.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "path 必填"));
            return;
        }
        var entry = configStore.set(path, params.path("value").asText(), getActor());
        audit("config.write", null, "set", on().put("path", path).toString());
        sendRes(id, true, on().put("path", entry.path()).put("revisionHash", entry.revisionHash())
                .put("reloadKind", configStore.reloadKind(path)));
    }

    private void patch(String id, JsonNode params) {
        String path = params.path("path").asText();
        if (path.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "path 必填"));
            return;
        }
        var entry = configStore.patch(path, params.path("patches").toString(), getActor());
        audit("config.write", null, "patch", on().put("path", path).toString());
        sendRes(id, true, on().put("path", entry.path()).put("revisionHash", entry.revisionHash())
                .put("reloadKind", configStore.reloadKind(path)));
    }

    private void list(String id) {
        sendRes(id, true, on().set("entries", (ArrayNode) ctx.om().valueToTree(configStore.list())));
    }
}
