package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** WS 帧协议处理器：req/res/event。 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final MessageStore store;
    private final EventBus bus;
    private final AgentLoop loop;
    private final com.lobster.permission.PermissionEngine permissions;
    private final com.lobster.store.InboxStore inbox;
    private final com.lobster.rbac.AgentRegistry agents;
    private final com.lobster.store.SessionOwnership ownership;
    private final com.lobster.store.SessionStateService stateService;
    private final com.lobster.store.TaskStore taskStore;
    private final com.lobster.store.WorkboardStore workboard;
    private final com.lobster.store.CronStore cron;
    private final com.lobster.store.MemoryStore memory;
    private final com.lobster.store.DreamingSweep dreaming;
    private final Map<WebSocketSession, Runnable> unsubscribes = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WsHandler(MessageStore store, EventBus bus, AgentLoop loop,
                     com.lobster.permission.PermissionEngine permissions,
                     com.lobster.store.InboxStore inbox,
                     com.lobster.rbac.AgentRegistry agents,
                     com.lobster.store.SessionOwnership ownership,
                     com.lobster.store.SessionStateService stateService,
                     com.lobster.store.TaskStore taskStore,
                     com.lobster.store.WorkboardStore workboard,
                     com.lobster.store.CronStore cron,
                     com.lobster.store.MemoryStore memory,
                     com.lobster.store.DreamingSweep dreaming) {
        this.store = store;
        this.bus = bus;
        this.loop = loop;
        this.permissions = permissions;
        this.inbox = inbox;
        this.agents = agents;
        this.ownership = ownership;
        this.stateService = stateService;
        this.taskStore = taskStore;
        this.workboard = workboard;
        this.cron = cron;
        this.memory = memory;
        this.dreaming = dreaming;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        // 全局事件转发为 event 帧
        Runnable unsub = bus.subscribeAll(e -> sendEvent(session, e));
        unsubscribes.put(session, unsub);
        // 连接即视为 connect 成功（M1 免鉴权）
        sendRes(session, "connect", true, OM.createObjectNode()
                .put("protocol", 1)
                .set("policy", OM.createObjectNode().put("maxPayload", 1048576)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Runnable unsub = unsubscribes.remove(session);
        if (unsub != null) unsub.run();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame = OM.readTree(message.getPayload());
        String type = frame.path("type").asText();
        if (!"req".equals(type)) return;
        String id = frame.path("id").asText();
        String method = frame.path("method").asText();
        JsonNode params = frame.path("params");
        try {
            handleReq(session, id, method, params);
        } catch (Exception e) {
            log.error("WS 方法处理失败: {}", method, e);
            ObjectNode err = OM.createObjectNode();
            err.put("code", "INTERNAL_ERROR");
            err.put("message", String.valueOf(e.getMessage()));
            sendRes(session, id, false, err);
        }
    }

    private void handleReq(WebSocketSession session, String id, String method, JsonNode params) throws Exception {
        switch (method) {
            case "connect" -> sendRes(session, id, true, OM.createObjectNode().put("protocol", 1));
            case "chat.send" -> chatSend(session, id, params);
            case "chat.history" -> chatHistory(session, id, params);
            case "sessions.list" -> sessionsList(session, id);
            case "permission.respond" -> permissionRespond(session, id, params);
            case "mode.set" -> modeSet(session, id, params);
            case "agents.list" -> agentsList(session, id);
            case "agents.create" -> agentsCreate(session, id, params);
            case "queue.mode.set" -> queueModeSet(session, id, params);
            case "sessions.archive" -> sessionArchive(session, id, params);
            case "sessions.rename" -> sessionRename(session, id, params);
            case "sessions.fork" -> sessionFork(session, id, params);
            case "sessions.rewind" -> sessionRewind(session, id, params);
            case "sessions.assignOwner" -> sessionAssignOwner(session, id, params);
            case "sessions.participants" -> sessionParticipants(session, id, params);
            case "sessions.state" -> sessionState(session, id, params);
            case "sessions.changesSince" -> sessionChangesSince(session, id, params);
            case "tasks.list" -> tasksList(session, id, params);
            case "tasks.get" -> tasksGet(session, id, params);
            case "tasks.cancel" -> tasksCancel(session, id, params);
            case "workboard.cards.list" -> workboardList(session, id, params);
            case "workboard.cards.create" -> workboardCreate(session, id, params);
            case "workboard.cards.update" -> workboardUpdate(session, id, params);
            case "workboard.cards.move" -> workboardMove(session, id, params);
            case "workboard.cards.delete" -> workboardDelete(session, id, params);
            case "workboard.cards.events" -> workboardEvents(session, id, params);
            case "cron.list" -> cronList(session, id);
            case "cron.get" -> cronGet(session, id, params);
            case "cron.add" -> cronAdd(session, id, params);
            case "cron.update" -> cronUpdate(session, id, params);
            case "cron.remove" -> cronRemove(session, id, params);
            case "cron.run" -> cronRun(session, id, params);
            case "cron.runs" -> cronRuns(session, id, params);
            case "memory.search" -> memorySearch(session, id, params);
            case "memory.recent" -> memoryRecent(session, id, params);
            case "memory.curated" -> memoryCurated(session, id);
            case "dreaming.sweep" -> dreamingSweep(session, id);
            default -> {
                ObjectNode err = OM.createObjectNode();
                err.put("code", "METHOD_NOT_FOUND");
                err.put("message", "未知方法: " + method);
                sendRes(session, id, false, err);
            }
        }
    }

    /** queue.mode.set：{sessionKey, mode: steer|followup|collect|interrupt}。 */
    private void queueModeSet(WebSocketSession session, String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        String mode = params.path("mode").asText("steer");
        var s = store.findByKey(sessionKey).orElse(null);
        if (s == null) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "SESSION_NOT_FOUND");
            err.put("message", "会话不存在: " + sessionKey);
            sendRes(session, id, false, err);
            return;
        }
        try {
            var m = com.lobster.agent.QueueMode.Mode.of(mode);
            loop.queueMode().setMode(s.id(), m);
            bus.publish(new LobsterEvent(com.lobster.event.Events.QUEUE_MODE_SET, s.id(),
                    OM.createObjectNode().put("mode", m.name().toLowerCase()), true));
            sendRes(session, id, true, OM.createObjectNode().put("mode", m.name().toLowerCase()));
        } catch (Exception e) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "INVALID_MODE");
            err.put("message", "未知队列模式: " + mode);
            sendRes(session, id, false, err);
        }
    }

    /** sessions.archive：{sessionKey}。 */
    private void sessionArchive(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        store.archive(s.id());
        sendRes(session, id, true, OM.createObjectNode().put("archived", true));
    }

    /** sessions.rename：{sessionKey, title}。 */
    private void sessionRename(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        store.setTitle(s.id(), params.path("title").asText());
        sendRes(session, id, true, OM.createObjectNode().put("renamed", true));
    }

    /** sessions.fork：{sessionKey, upToMessageId, newKey}。 */
    private void sessionFork(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        var forked = store.fork(s.id(), params.path("upToMessageId").asText(),
                params.path("newKey").asText("fork-" + System.currentTimeMillis()));
        sendRes(session, id, true, OM.createObjectNode()
                .put("id", forked.id()).put("sessionKey", forked.sessionKey()));
    }

    /** sessions.rewind：{sessionKey, upToMessageId}。 */
    private void sessionRewind(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        store.rewind(s.id(), params.path("upToMessageId").asText());
        sendRes(session, id, true, OM.createObjectNode().put("rewound", true));
    }

    /** sessions.state：{sessionKey} -> 当前 stateVersion。 */
    private void sessionState(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        sendRes(session, id, true, OM.createObjectNode()
                .put("stateVersion", stateService.getVersion(s.id())));
    }

    /** sessions.changesSince：{sessionKey, since} -> 该版本之后的信号。 */
    private void sessionChangesSince(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        long since = params.path("since").asLong(0);
        ArrayNode arr = OM.createArrayNode();
        for (var sig : stateService.changesSince(s.id(), since)) {
            arr.add(OM.createObjectNode()
                    .put("stateVersion", sig.stateVersion())
                    .put("kind", sig.kind())
                    .put("payload", sig.payload())
                    .put("createdAt", sig.createdAt()));
        }
        sendRes(session, id, true, OM.createObjectNode()
                .put("currentVersion", stateService.getVersion(s.id()))
                .set("signals", arr));
    }

    /** sessions.assignOwner：{sessionKey, owner}。 */
    private void sessionAssignOwner(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        ownership.assignOwner(s.id(), params.path("owner").asText());
        sendRes(session, id, true, OM.createObjectNode().put("assigned", true));
    }

    /** sessions.participants：{sessionKey}。 */
    private void sessionParticipants(WebSocketSession session, String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND")); return; }
        ArrayNode arr = OM.createArrayNode();
        for (var p : ownership.listParticipants(s.id())) {
            arr.add(OM.createObjectNode().put("actorId", p.actorId()).put("lastAt", p.lastAt()));
        }
        sendRes(session, id, true, OM.createObjectNode()
                .put("creator", ownership.creator(s.id()))
                .put("owner", ownership.owner(s.id()))
                .set("participants", arr));
    }

    /** tasks.list：{status?, ownerKey?}。 */
    private void tasksList(WebSocketSession session, String id, JsonNode params) {
        String status = params.path("status").asText("");
        String ownerKey = params.path("ownerKey").asText("");
        var tasks = (status.isEmpty() && ownerKey.isEmpty()) ? taskStore.list()
                : !status.isEmpty() ? taskStore.listByStatus(
                        com.lobster.store.TaskStore.Status.valueOf(status.toUpperCase()))
                : taskStore.listByOwner(ownerKey);
        ArrayNode arr = OM.createArrayNode();
        for (var t : tasks) arr.add(taskJson(t));
        sendRes(session, id, true, OM.createObjectNode().set("tasks", arr));
    }

    /** tasks.get：{taskId}。 */
    private void tasksGet(WebSocketSession session, String id, JsonNode params) {
        String taskId = params.path("taskId").asText();
        var t = taskStore.get(taskId);
        if (t.isEmpty()) {
            sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND"));
            return;
        }
        sendRes(session, id, true, taskJson(t.get()));
    }

    /** tasks.cancel：{taskId}。 */
    private void tasksCancel(WebSocketSession session, String id, JsonNode params) {
        String taskId = params.path("taskId").asText();
        boolean ok = taskStore.cancel(taskId);
        sendRes(session, id, ok, ok ? OM.createObjectNode().put("cancelled", true)
                : OM.createObjectNode().put("code","NOT_CANCELABLE").put("message","任务已终结或不存在"));
    }

    private ObjectNode taskJson(com.lobster.store.TaskStore.TaskRecord t) {
        return OM.createObjectNode()
                .put("id", t.id())
                .put("runtime", t.runtime())
                .put("taskKind", t.taskKind())
                .put("ownerKey", t.ownerKey())
                .put("agentId", t.agentId())
                .put("runId", t.runId())
                .put("label", t.label())
                .put("taskText", t.taskText())
                .put("status", t.status())
                .put("notifyPolicy", t.notifyPolicy())
                .put("toolUseCount", t.toolUseCount())
                .put("lastToolName", t.lastToolName())
                .put("error", t.error())
                .put("progressSummary", t.progressSummary())
                .put("terminalSummary", t.terminalSummary())
                .put("createdAt", t.createdAt())
                .put("startedAt", t.startedAt() != null ? t.startedAt() : 0)
                .put("endedAt", t.endedAt() != null ? t.endedAt() : 0);
    }

    /** workboard.cards.list：{boardId?, status?}。 */
    private void workboardList(WebSocketSession session, String id, JsonNode params) {
        String boardId = params.path("boardId").asText("main");
        String status = params.path("status").asText("");
        var cards = status.isEmpty()
                ? workboard.listCards(boardId)
                : workboard.listByStatus(boardId,
                        com.lobster.store.WorkboardStore.Status.valueOf(status.toUpperCase()));
        ArrayNode arr = OM.createArrayNode();
        for (var c : cards) arr.add(cardJson(c));
        sendRes(session, id, true, OM.createObjectNode().set("cards", arr));
    }

    /** workboard.cards.create：{boardId?, title, description?, status?, priority?, ...}。 */
    private void workboardCreate(WebSocketSession session, String id, JsonNode params) {
        String title = params.path("title").asText();
        if (title.isEmpty()) {
            sendRes(session, id, false, OM.createObjectNode().put("code","BAD_REQUEST").put("message","title 必填"));
            return;
        }
        var status = parseStatus(params.path("status").asText("triage"));
        var priority = parsePriority(params.path("priority").asText("normal"));
        var c = workboard.createCard(
                params.path("boardId").asText("main"),
                title,
                params.path("description").asText(null),
                status, priority,
                params.path("assignedAgentId").asText(null),
                params.path("assignedUserId").asText(null),
                params.path("linkedTaskId").asText(null),
                params.path("linkedRunId").asText(null),
                params.path("linkedSessionKey").asText(null),
                params.path("labels").asText(null),
                params.path("metadata").asText(null));
        sendRes(session, id, true, cardJson(c));
    }

    /** workboard.cards.update：{cardId, title?, description?, priority?, labels?, metadata?}。 */
    private void workboardUpdate(WebSocketSession session, String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        var existing = workboard.getCard(cardId);
        if (existing.isEmpty()) {
            sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND"));
            return;
        }
        var c = existing.get();
        workboard.updateCard(cardId,
                params.has("title") ? params.path("title").asText() : c.title(),
                params.has("description") ? params.path("description").asText() : c.description(),
                params.has("priority") ? parsePriority(params.path("priority").asText()) : com.lobster.store.WorkboardStore.Priority.valueOf(c.priority().toUpperCase()),
                params.has("labels") ? params.path("labels").asText() : c.labels(),
                params.has("metadata") ? params.path("metadata").asText() : c.metadata());
        sendRes(session, id, true, cardJson(workboard.getCard(cardId).orElseThrow()));
    }

    /** workboard.cards.move：{cardId, status, position?}。 */
    private void workboardMove(WebSocketSession session, String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        String statusStr = params.path("status").asText();
        if (cardId.isEmpty() || statusStr.isEmpty()) {
            sendRes(session, id, false, OM.createObjectNode().put("code","BAD_REQUEST"));
            return;
        }
        try {
            var status = com.lobster.store.WorkboardStore.Status.valueOf(statusStr.toUpperCase());
            Double pos = params.has("position") ? params.path("position").asDouble() : null;
            workboard.moveCard(cardId, status, pos);
            sendRes(session, id, true, cardJson(workboard.getCard(cardId).orElseThrow()));
        } catch (IllegalArgumentException e) {
            sendRes(session, id, false, OM.createObjectNode().put("code","INVALID_STATUS"));
        }
    }

    /** workboard.cards.delete：{cardId}。 */
    private void workboardDelete(WebSocketSession session, String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        workboard.deleteCard(cardId);
        sendRes(session, id, true, OM.createObjectNode().put("deleted", true));
    }

    /** workboard.cards.events：{cardId} -> 事件历史。 */
    private void workboardEvents(WebSocketSession session, String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        ArrayNode arr = OM.createArrayNode();
        for (var e : workboard.listEvents(cardId)) {
            arr.add(OM.createObjectNode()
                    .put("id", e.id())
                    .put("kind", e.kind())
                    .put("actor", e.actor())
                    .put("payload", e.payload())
                    .put("createdAt", e.createdAt()));
        }
        sendRes(session, id, true, OM.createObjectNode().set("events", arr));
    }

    private ObjectNode cardJson(com.lobster.store.WorkboardStore.Card c) {
        return OM.createObjectNode()
                .put("id", c.id())
                .put("boardId", c.boardId())
                .put("status", c.status())
                .put("priority", c.priority())
                .put("labels", c.labels())
                .put("title", c.title())
                .put("description", c.description())
                .put("assignedAgentId", c.assignedAgentId())
                .put("linkedTaskId", c.linkedTaskId())
                .put("linkedSessionKey", c.linkedSessionKey())
                .put("position", c.position())
                .put("archived", c.archived())
                .put("createdAt", c.createdAt())
                .put("updatedAt", c.updatedAt());
    }

    private com.lobster.store.WorkboardStore.Status parseStatus(String s) {
        return com.lobster.store.WorkboardStore.Status.valueOf(s.toUpperCase());
    }

    private com.lobster.store.WorkboardStore.Priority parsePriority(String s) {
        return com.lobster.store.WorkboardStore.Priority.valueOf(s.toUpperCase());
    }

    /** cron.list：全部 cron job。 */
    private void cronList(WebSocketSession session, String id) {
        ArrayNode arr = OM.createArrayNode();
        for (var j : cron.list()) arr.add(cronJobJson(j));
        sendRes(session, id, true, OM.createObjectNode().set("jobs", arr));
    }

    /** cron.get：{jobId}。 */
    private void cronGet(WebSocketSession session, String id, JsonNode params) {
        var job = cron.get(params.path("jobId").asText());
        if (job.isEmpty()) {
            sendRes(session, id, false, OM.createObjectNode().put("code","NOT_FOUND"));
            return;
        }
        sendRes(session, id, true, cronJobJson(job.get()));
    }

    /** cron.add：{agentId, name, schedule, prompt, sessionPolicy?}。 */
    private void cronAdd(WebSocketSession session, String id, JsonNode params) {
        try {
            var job = cron.create(
                    params.path("agentId").asText("main"),
                    params.path("name").asText(),
                    params.path("schedule").asText(),
                    params.path("prompt").asText(),
                    params.path("sessionPolicy").asText(null));
            sendRes(session, id, true, cronJobJson(job));
        } catch (IllegalArgumentException e) {
            sendRes(session, id, false, OM.createObjectNode().put("code","INVALID_SCHEDULE")
                    .put("message", e.getMessage()));
        }
    }

    /** cron.update：{jobId, name?, schedule?, prompt?, sessionPolicy?, enabled?}。 */
    private void cronUpdate(WebSocketSession session, String id, JsonNode params) {
        try {
            var job = cron.update(
                    params.path("jobId").asText(),
                    params.has("name") ? params.path("name").asText() : null,
                    params.has("schedule") ? params.path("schedule").asText() : null,
                    params.has("prompt") ? params.path("prompt").asText() : null,
                    params.has("sessionPolicy") ? params.path("sessionPolicy").asText() : null,
                    params.has("enabled") ? params.path("enabled").asBoolean() : null);
            sendRes(session, id, true, cronJobJson(job));
        } catch (Exception e) {
            sendRes(session, id, false, OM.createObjectNode().put("code","ERROR")
                    .put("message", e.getMessage()));
        }
    }

    /** cron.remove：{jobId}。 */
    private void cronRemove(WebSocketSession session, String id, JsonNode params) {
        cron.remove(params.path("jobId").asText());
        sendRes(session, id, true, OM.createObjectNode().put("removed", true));
    }

    /** cron.run：{jobId} -> 手动触发。 */
    private void cronRun(WebSocketSession session, String id, JsonNode params) {
        var run = cron.runOnce(params.path("jobId").asText());
        sendRes(session, id, true, OM.createObjectNode()
                .put("runId", run.id())
                .put("status", run.status()));
    }

    /** cron.runs：{jobId} -> 运行历史。 */
    private void cronRuns(WebSocketSession session, String id, JsonNode params) {
        ArrayNode arr = OM.createArrayNode();
        for (var r : cron.listRuns(params.path("jobId").asText())) {
            arr.add(OM.createObjectNode()
                    .put("id", r.id())
                    .put("jobId", r.jobId())
                    .put("fireAt", r.fireAt())
                    .put("startedAt", r.startedAt() != null ? r.startedAt() : 0)
                    .put("endedAt", r.endedAt() != null ? r.endedAt() : 0)
                    .put("status", r.status())
                    .put("runId", r.runId())
                    .put("error", r.error()));
        }
        sendRes(session, id, true, OM.createObjectNode().set("runs", arr));
    }

    private ObjectNode cronJobJson(com.lobster.store.CronStore.CronJob j) {
        return OM.createObjectNode()
                .put("id", j.id())
                .put("agentId", j.agentId())
                .put("name", j.name())
                .put("schedule", j.schedule())
                .put("prompt", j.prompt())
                .put("sessionPolicy", j.sessionPolicy())
                .put("enabled", j.enabled())
                .put("nextFireAt", j.nextFireAt() != null ? j.nextFireAt() : 0)
                .put("createdAt", j.createdAt())
                .put("updatedAt", j.updatedAt());
    }

    /** memory.search：{query, limit?}。 */
    private void memorySearch(WebSocketSession session, String id, JsonNode params) {
        String query = params.path("query").asText();
        int limit = params.path("limit").asInt(10);
        ArrayNode arr = OM.createArrayNode();
        for (var c : memory.search(query, limit)) {
            arr.add(OM.createObjectNode()
                    .put("id", c.id())
                    .put("content", c.content())
                    .put("originClass", c.originClass())
                    .put("createdAt", c.createdAt()));
        }
        sendRes(session, id, true, OM.createObjectNode().set("results", arr));
    }

    /** memory.recent：{days?, limit?} -> 近期 episodic 记忆。 */
    private void memoryRecent(WebSocketSession session, String id, JsonNode params) {
        int days = params.path("days").asInt(2);
        int limit = params.path("limit").asInt(20);
        ArrayNode arr = OM.createArrayNode();
        for (var c : memory.recentEpisodic(days, limit)) {
            arr.add(OM.createObjectNode()
                    .put("id", c.id())
                    .put("content", c.content())
                    .put("originClass", c.originClass())
                    .put("createdAt", c.createdAt()));
        }
        sendRes(session, id, true, OM.createObjectNode().set("results", arr));
    }

    /** memory.curated：curated 记忆列表。 */
    private void memoryCurated(WebSocketSession session, String id) {
        ArrayNode arr = OM.createArrayNode();
        for (var c : memory.curated()) {
            arr.add(OM.createObjectNode()
                    .put("id", c.id())
                    .put("content", c.content())
                    .put("createdAt", c.createdAt()));
        }
        sendRes(session, id, true, OM.createObjectNode().set("results", arr));
    }

    /** dreaming.sweep：手动触发 Dreaming 整合。 */
    private void dreamingSweep(WebSocketSession session, String id) {
        var result = dreaming.sweep();
        sendRes(session, id, true, OM.createObjectNode()
                .put("reviewed", result.reviewed())
                .put("promoted", result.promoted())
                .put("report", result.report()));
    }

    /** agents.list：全部 agent（角色实例）。 */
    private void agentsList(WebSocketSession session, String id) {
        ArrayNode arr = OM.createArrayNode();
        for (var a : agents.list()) {
            ObjectNode n = OM.createObjectNode()
                    .put("id", a.id())
                    .put("name", a.name())
                    .put("role", a.role())
                    .put("emoji", a.emoji())
                    .put("modelId", a.modelId())
                    .put("workspaceDir", a.workspaceDir());
            n.set("allowedTools", OM.valueToTree(
                    com.lobster.rbac.Role.of(a.role()).allowedTools()));
            arr.add(n);
        }
        sendRes(session, id, true, OM.createObjectNode().set("agents", arr));
    }

    /** agents.create：{name, role, emoji?, modelProvider?, modelId?}。 */
    private void agentsCreate(WebSocketSession session, String id, JsonNode params) {
        String name = params.path("name").asText();
        String role = params.path("role").asText();
        if (name.isEmpty() || role.isEmpty()) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "INVALID_PARAMS");
            err.put("message", "name 与 role 必填");
            sendRes(session, id, false, err);
            return;
        }
        try {
            var a = agents.create(name, role, params.path("emoji").asText(null),
                    params.path("modelProvider").asText(null), params.path("modelId").asText(null));
            ObjectNode payload = OM.createObjectNode()
                    .put("id", a.id())
                    .put("name", a.name())
                    .put("role", a.role())
                    .put("emoji", a.emoji());
            payload.set("allowedTools", OM.valueToTree(
                    com.lobster.rbac.Role.of(a.role()).allowedTools()));
            sendRes(session, id, true, payload);
        } catch (IllegalArgumentException e) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "INVALID_ROLE");
            err.put("message", String.valueOf(e.getMessage()));
            sendRes(session, id, false, err);
        }
    }

    /** Plan/Build 模式切换：mode.set {sessionKey, mode: "plan"|"build"}。 */
    private void modeSet(WebSocketSession session, String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        String mode = params.path("mode").asText("build");
        var s = store.findByKey(sessionKey).orElse(null);
        if (s == null) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "SESSION_NOT_FOUND");
            err.put("message", "会话不存在: " + sessionKey);
            sendRes(session, id, false, err);
            return;
        }
        boolean plan = "plan".equals(mode);
        loop.planMode().setPlan(s.id(), plan);
        bus.publish(new LobsterEvent(com.lobster.event.Events.MODE_SWITCHED, s.id(),
                OM.createObjectNode().put("mode", plan ? "plan" : "build"), true));
        sendRes(session, id, true, OM.createObjectNode().put("mode", plan ? "plan" : "build"));
    }

    private void chatSend(WebSocketSession session, String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        String text = params.path("text").asText();
        var existing = store.findByKey(sessionKey);
        var s = existing.orElseGet(() ->
                store.createSession(sessionKey, "main", System.getProperty("user.dir")));
        if (loop.isBusy(s.id())) {
            // busy：按队列模式分流（steer/followup/collect/interrupt）
            var disp = loop.queueMode().dispatch(s.id(), true,
                    ignore -> inbox.enqueue(s.id(), text),
                    () -> loop.requestAbort(s.id()));
            bus.publish(new LobsterEvent("session.input.queued", s.id(),
                    OM.createObjectNode().put("text", text)
                            .put("mode", disp.mode().name().toLowerCase())
                            .put("note", disp.note()), false));
        } else {
            store.appendUser(s.id(), List.of(new Part.Text(text, false, false)));
            bus.publish(new LobsterEvent(com.lobster.event.Events.PROMPT_ADMITTED, s.id(),
                    OM.createObjectNode().put("text", text), true));
            // 虚拟线程执行 loop，立即返回 ack
            Thread.ofVirtual().name("agent-loop-" + s.id()).start(() -> loop.run(s.id()));
        }
        ObjectNode payload = OM.createObjectNode()
                .put("runId", s.id())
                .put("status", "started");
        sendRes(session, id, true, payload);
    }

    private void chatHistory(WebSocketSession session, String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        var s = store.findByKey(sessionKey);
        ObjectNode payload = OM.createObjectNode();
        ArrayNode messages = OM.createArrayNode();
        if (s.isPresent()) {
            for (Message m : store.loadActive(s.get().id())) {
                ObjectNode node = OM.createObjectNode();
                node.put("id", m.id());
                node.put("role", m.role());
                node.put("createdAt", m.createdAt());
                ArrayNode parts = OM.createArrayNode();
                if (m.parts() != null) {
                    for (Part p : m.parts()) parts.add(OM.valueToTree(p));
                }
                node.set("parts", parts);
                messages.add(node);
            }
        }
        payload.set("messages", messages);
        sendRes(session, id, true, payload);
    }

    private void sessionsList(WebSocketSession session, String id) {
        ObjectNode payload = OM.createObjectNode();
        ArrayNode list = OM.createArrayNode();
        payload.set("sessions", list);
        sendRes(session, id, true, payload);
    }

    private void permissionRespond(WebSocketSession session, String id, JsonNode params) {
        String requestId = params.path("requestId").asText();
        String decision = params.path("decision").asText();
        if (requestId.isEmpty()) {
            ObjectNode err = OM.createObjectNode();
            err.put("code", "BAD_REQUEST").put("message", "requestId 必填");
            sendRes(session, id, false, err);
            return;
        }
        permissions.reply(requestId, RuntimeConfig.toReply(decision));
        bus.publish(new LobsterEvent(com.lobster.event.Events.PERMISSION_REPLIED, null,
                OM.createObjectNode()
                        .put("requestId", requestId)
                        .put("decision", decision == null || decision.isEmpty() ? "REJECT" : decision), false));
        sendRes(session, id, true, OM.createObjectNode().put("requestId", requestId));
    }

    private void sendRes(WebSocketSession session, String id, boolean ok, JsonNode payload) {
        ObjectNode frame = OM.createObjectNode();
        frame.put("type", "res");
        frame.put("id", id);
        frame.put("ok", ok);
        if (ok) frame.set("payload", payload);
        else frame.set("error", payload);
        send(session, frame);
    }

    private void sendEvent(WebSocketSession session, LobsterEvent e) {
        ObjectNode frame = OM.createObjectNode();
        frame.put("type", "event");
        frame.put("event", e.type());
        frame.set("payload", e.data());
        if (e.durable()) frame.put("seq", e.seq());
        send(session, frame);
    }

    private void send(WebSocketSession session, JsonNode frame) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(OM.writeValueAsString(frame)));
            }
        } catch (Exception e) {
            log.warn("WS 发送失败: {}", e.getMessage());
        }
    }
}
