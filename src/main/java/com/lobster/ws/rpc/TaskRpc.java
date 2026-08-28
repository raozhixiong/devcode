package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.TaskStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 任务台账 RPC（M4-17）。 */
@Component
public class TaskRpc extends BaseRpc {

    private final TaskStore taskStore;

    public TaskRpc(TaskStore taskStore) { this.taskStore = taskStore; }

    @Override
    public Set<String> methods() { return Set.of("tasks.list", "tasks.get", "tasks.cancel"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "tasks.list" -> tasksList(id, params);
            case "tasks.get" -> tasksGet(id, params);
            case "tasks.cancel" -> tasksCancel(id, params);
        }
    }

    private void tasksList(String id, JsonNode params) {
        String status = params.path("status").asText("");
        String ownerKey = params.path("ownerKey").asText("");
        var tasks = (status.isEmpty() && ownerKey.isEmpty()) ? taskStore.list()
                : !status.isEmpty() ? taskStore.listByStatus(TaskStore.Status.valueOf(status.toUpperCase()))
                : taskStore.listByOwner(ownerKey);
        ArrayNode arr = arr();
        for (var t : tasks) arr.add(taskJson(t));
        sendRes(id, true, on().set("tasks", arr));
    }

    private void tasksGet(String id, JsonNode params) {
        var t = taskStore.get(params.path("taskId").asText());
        if (t.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        sendRes(id, true, taskJson(t.get()));
    }

    private void tasksCancel(String id, JsonNode params) {
        boolean ok = taskStore.cancel(params.path("taskId").asText());
        sendRes(id, ok, ok ? on().put("cancelled", true)
                : on().put("code", "NOT_CANCELABLE").put("message", "任务已终结或不存在"));
    }

    private ObjectNode taskJson(TaskStore.TaskRecord t) {
        return on().put("id", t.id()).put("runtime", t.runtime()).put("taskKind", t.taskKind())
                .put("ownerKey", t.ownerKey()).put("agentId", t.agentId()).put("runId", t.runId())
                .put("label", t.label()).put("taskText", t.taskText()).put("status", t.status())
                .put("notifyPolicy", t.notifyPolicy()).put("toolUseCount", t.toolUseCount())
                .put("lastToolName", t.lastToolName()).put("error", t.error())
                .put("progressSummary", t.progressSummary()).put("terminalSummary", t.terminalSummary())
                .put("createdAt", t.createdAt())
                .put("startedAt", t.startedAt() != null ? t.startedAt() : 0)
                .put("endedAt", t.endedAt() != null ? t.endedAt() : 0);
    }
}
