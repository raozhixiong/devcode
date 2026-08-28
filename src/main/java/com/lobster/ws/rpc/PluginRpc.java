package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.PluginMarketplace;
import com.lobster.store.PluginStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 插件 / 市场 RPC（M5-28 / FR-H-4）。 */
@Component
public class PluginRpc extends BaseRpc {

    private final PluginStore pluginStore;
    private final PluginMarketplace pluginMarketplace;

    public PluginRpc(PluginStore pluginStore, PluginMarketplace pluginMarketplace) {
        this.pluginStore = pluginStore;
        this.pluginMarketplace = pluginMarketplace;
    }

    @Override
    public Set<String> methods() {
        return Set.of("plugins.list", "plugins.install", "plugins.setEnabled", "plugins.uninstall", "plugins.marketplace");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "plugins.list" -> list(id);
            case "plugins.install" -> install(id, params);
            case "plugins.setEnabled" -> setEnabled(id, params);
            case "plugins.uninstall" -> uninstall(id, params);
            case "plugins.marketplace" -> marketplace(id);
        }
    }

    private void list(String id) {
        sendRes(id, true, on().set("plugins", (ArrayNode) ctx.om().valueToTree(pluginStore.list())));
    }

    private void install(String id, JsonNode params) {
        String name = params.path("name").asText();
        String source = params.path("source").asText();
        String version = params.path("version").asText(null);
        if (name.isEmpty() || source.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "name 和 source 必填"));
            return;
        }
        var plugin = pluginStore.install(name, source, version);
        audit("plugin.install", null, "installed", on().put("pluginId", plugin.id()).toString());
        sendRes(id, true, on().put("pluginId", plugin.id()).put("name", plugin.name()).put("enabled", plugin.enabled()));
    }

    private void setEnabled(String id, JsonNode params) {
        String pluginId = params.path("id").asText();
        boolean enabled = params.path("enabled").asBoolean(true);
        if (pluginId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        pluginStore.setEnabled(pluginId, enabled);
        sendRes(id, true, on().put("pluginId", pluginId).put("enabled", enabled));
    }

    private void uninstall(String id, JsonNode params) {
        String pluginId = params.path("id").asText();
        if (pluginId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "id 必填"));
            return;
        }
        pluginStore.uninstall(pluginId);
        sendRes(id, true, on().put("pluginId", pluginId));
    }

    private void marketplace(String id) {
        sendRes(id, true, on().set("plugins", (ArrayNode) ctx.om().valueToTree(pluginMarketplace.catalog())));
    }
}
