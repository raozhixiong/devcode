package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.DreamingSweep;
import com.lobster.store.MemoryStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 记忆 RPC：memory.search / memory.recent / memory.curated / dreaming.sweep（M4-20）。 */
@Component
public class MemoryRpc extends BaseRpc {

    private final MemoryStore memory;
    private final DreamingSweep dreaming;

    public MemoryRpc(MemoryStore memory, DreamingSweep dreaming) {
        this.memory = memory;
        this.dreaming = dreaming;
    }

    @Override
    public Set<String> methods() {
        return Set.of("memory.search", "memory.recent", "memory.curated", "dreaming.sweep");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "memory.search" -> search(id, params);
            case "memory.recent" -> recent(id, params);
            case "memory.curated" -> curated(id);
            case "dreaming.sweep" -> sweep(id);
        }
    }

    private void search(String id, JsonNode params) {
        ArrayNode arr = arr();
        for (var c : memory.search(params.path("query").asText(), params.path("limit").asInt(10))) {
            arr.add(on().put("id", c.id()).put("content", c.content())
                    .put("originClass", c.originClass()).put("createdAt", c.createdAt()));
        }
        sendRes(id, true, on().set("results", arr));
    }

    private void recent(String id, JsonNode params) {
        ArrayNode arr = arr();
        for (var c : memory.recentEpisodic(params.path("days").asInt(2), params.path("limit").asInt(20))) {
            arr.add(on().put("id", c.id()).put("content", c.content())
                    .put("originClass", c.originClass()).put("createdAt", c.createdAt()));
        }
        sendRes(id, true, on().set("results", arr));
    }

    private void curated(String id) {
        ArrayNode arr = arr();
        for (var c : memory.curated()) {
            arr.add(on().put("id", c.id()).put("content", c.content()).put("createdAt", c.createdAt()));
        }
        sendRes(id, true, on().set("results", arr));
    }

    private void sweep(String id) {
        var result = dreaming.sweep();
        sendRes(id, true, on().put("reviewed", result.reviewed())
                .put("promoted", result.promoted()).put("report", result.report()));
    }
}
