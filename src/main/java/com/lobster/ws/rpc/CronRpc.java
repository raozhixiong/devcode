package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.CronStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** Cron 调度 RPC（M4-19）。 */
@Component
public class CronRpc extends BaseRpc {

    private final CronStore cron;

    public CronRpc(CronStore cron) { this.cron = cron; }

    @Override
    public Set<String> methods() {
        return Set.of("cron.list", "cron.get", "cron.add", "cron.update",
                "cron.remove", "cron.run", "cron.runs");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "cron.list" -> list(id);
            case "cron.get" -> get(id, params);
            case "cron.add" -> add(id, params);
            case "cron.update" -> update(id, params);
            case "cron.remove" -> remove(id, params);
            case "cron.run" -> run(id, params);
            case "cron.runs" -> runs(id, params);
        }
    }

    private void list(String id) {
        ArrayNode arr = arr();
        for (var j : cron.list()) arr.add(cronJobJson(j));
        sendRes(id, true, on().set("jobs", arr));
    }

    private void get(String id, JsonNode params) {
        var job = cron.get(params.path("jobId").asText());
        if (job.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        sendRes(id, true, cronJobJson(job.get()));
    }

    private void add(String id, JsonNode params) {
        try {
            var job = cron.create(params.path("agentId").asText("main"), params.path("name").asText(),
                    params.path("schedule").asText(), params.path("prompt").asText(),
                    params.path("sessionPolicy").asText(null));
            sendRes(id, true, cronJobJson(job));
        } catch (IllegalArgumentException e) {
            sendRes(id, false, on().put("code", "INVALID_SCHEDULE").put("message", e.getMessage()));
        }
    }

    private void update(String id, JsonNode params) {
        try {
            var job = cron.update(params.path("jobId").asText(),
                    params.has("name") ? params.path("name").asText() : null,
                    params.has("schedule") ? params.path("schedule").asText() : null,
                    params.has("prompt") ? params.path("prompt").asText() : null,
                    params.has("sessionPolicy") ? params.path("sessionPolicy").asText() : null,
                    params.has("enabled") ? params.path("enabled").asBoolean() : null);
            sendRes(id, true, cronJobJson(job));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "ERROR").put("message", e.getMessage()));
        }
    }

    private void remove(String id, JsonNode params) {
        cron.remove(params.path("jobId").asText());
        sendRes(id, true, on().put("removed", true));
    }

    private void run(String id, JsonNode params) {
        var r = cron.runOnce(params.path("jobId").asText());
        sendRes(id, true, on().put("runId", r.id()).put("status", r.status()));
    }

    private void runs(String id, JsonNode params) {
        ArrayNode arr = arr();
        for (var r : cron.listRuns(params.path("jobId").asText())) {
            arr.add(on().put("id", r.id()).put("jobId", r.jobId()).put("fireAt", r.fireAt())
                    .put("startedAt", r.startedAt() != null ? r.startedAt() : 0)
                    .put("endedAt", r.endedAt() != null ? r.endedAt() : 0)
                    .put("status", r.status()).put("runId", r.runId()).put("error", r.error()));
        }
        sendRes(id, true, on().set("runs", arr));
    }

    private ObjectNode cronJobJson(CronStore.CronJob j) {
        return on().put("id", j.id()).put("agentId", j.agentId()).put("name", j.name())
                .put("schedule", j.schedule()).put("prompt", j.prompt()).put("sessionPolicy", j.sessionPolicy())
                .put("enabled", j.enabled()).put("nextFireAt", j.nextFireAt() != null ? j.nextFireAt() : 0)
                .put("createdAt", j.createdAt()).put("updatedAt", j.updatedAt());
    }
}
