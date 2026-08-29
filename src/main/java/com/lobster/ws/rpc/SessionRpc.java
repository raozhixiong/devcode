package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.agent.QueueMode;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.permission.PermissionEngine;
import com.lobster.rbac.AgentRegistry;
import com.lobster.store.InboxStore;
import com.lobster.store.MessageStore;
import com.lobster.store.SessionOwnership;
import com.lobster.store.SessionStateService;
import com.lobster.ws.RuntimeConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 会话 / 队列模式 / Plan 模式 / 权限应答 RPC。 */
@Component
public class SessionRpc extends BaseRpc {

    private final MessageStore store;
    private final AgentLoop loop;
    private final InboxStore inbox;
    private final PermissionEngine permissions;
    private final SessionOwnership ownership;
    private final SessionStateService stateService;
    private final AgentRegistry agents;

    public SessionRpc(MessageStore store, AgentLoop loop, InboxStore inbox,
                      PermissionEngine permissions, SessionOwnership ownership,
                      SessionStateService stateService, AgentRegistry agents) {
        this.store = store;
        this.loop = loop;
        this.inbox = inbox;
        this.permissions = permissions;
        this.ownership = ownership;
        this.stateService = stateService;
        this.agents = agents;
    }

    @Override
    public Set<String> methods() {
        return Set.of("queue.mode.set", "mode.set", "sessions.archive", "sessions.rename",
                "sessions.fork", "sessions.rewind", "sessions.assignOwner", "sessions.participants",
                "sessions.state", "sessions.changesSince", "permission.respond", "sessions.list");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "queue.mode.set" -> queueModeSet(id, params);
            case "mode.set" -> modeSet(id, params);
            case "sessions.archive" -> sessionArchive(id, params);
            case "sessions.rename" -> sessionRename(id, params);
            case "sessions.fork" -> sessionFork(id, params);
            case "sessions.rewind" -> sessionRewind(id, params);
            case "sessions.assignOwner" -> sessionAssignOwner(id, params);
            case "sessions.participants" -> sessionParticipants(id, params);
            case "sessions.state" -> sessionState(id, params);
            case "sessions.changesSince" -> sessionChangesSince(id, params);
            case "permission.respond" -> permissionRespond(id, params);
            case "sessions.list" -> sessionsList(id);
        }
    }

    private void notFound(String id) { sendRes(id, false, on().put("code", "NOT_FOUND")); }

    private void queueModeSet(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        try {
            var m = QueueMode.Mode.of(params.path("mode").asText());
            loop.queueMode().setMode(s.id(), m);
            publish(new LobsterEvent(Events.QUEUE_MODE_SET, s.id(),
                    on().put("mode", m.name().toLowerCase()), true));
            sendRes(id, true, on().put("mode", m.name().toLowerCase()));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "INVALID_MODE").put("message", "未知队列模式: " + params.path("mode").asText()));
        }
    }

    private void sessionArchive(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        store.archive(s.id());
        sendRes(id, true, on().put("archived", true));
    }

    private void sessionRename(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        store.setTitle(s.id(), params.path("title").asText());
        sendRes(id, true, on().put("renamed", true));
    }

    private void sessionFork(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        var forked = store.fork(s.id(), params.path("upToMessageId").asText(),
                params.path("newKey").asText("fork-" + System.currentTimeMillis()));
        sendRes(id, true, on().put("id", forked.id()).put("sessionKey", forked.sessionKey()));
    }

    private void sessionRewind(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        store.rewind(s.id(), params.path("upToMessageId").asText());
        sendRes(id, true, on().put("rewound", true));
    }

    private void sessionState(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        sendRes(id, true, on().put("stateVersion", stateService.getVersion(s.id())));
    }

    private void sessionChangesSince(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        long since = params.path("since").asLong(0);
        ArrayNode a = arr();
        for (var sig : stateService.changesSince(s.id(), since)) {
            a.add(on().put("stateVersion", sig.stateVersion()).put("kind", sig.kind())
                    .put("payload", sig.payload()).put("createdAt", sig.createdAt()));
        }
        sendRes(id, true, on().put("currentVersion", stateService.getVersion(s.id())).set("signals", a));
    }

    private void sessionAssignOwner(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        ownership.assignOwner(s.id(), params.path("owner").asText());
        sendRes(id, true, on().put("assigned", true));
    }

    private void sessionParticipants(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) { notFound(id); return; }
        ArrayNode a = arr();
        for (var p : ownership.listParticipants(s.id())) {
            a.add(on().put("actorId", p.actorId()).put("lastAt", p.lastAt()));
        }
        sendRes(id, true, on().put("creator", ownership.creator(s.id()))
                .put("owner", ownership.owner(s.id())).set("participants", a));
    }

    private void modeSet(String id, JsonNode params) {
        var s = store.findByKey(params.path("sessionKey").asText("main")).orElse(null);
        if (s == null) {
            sendRes(id, false, on().put("code", "SESSION_NOT_FOUND").put("message", "会话不存在: " + params.path("sessionKey").asText()));
            return;
        }
        boolean plan = "plan".equals(params.path("mode").asText("build"));
        loop.planMode().setPlan(s.id(), plan);
        publish(new LobsterEvent(Events.MODE_SWITCHED, s.id(),
                on().put("mode", plan ? "plan" : "build"), true));
        sendRes(id, true, on().put("mode", plan ? "plan" : "build"));
    }

    private void permissionRespond(String id, JsonNode params) {
        String requestId = params.path("requestId").asText();
        String decision = params.path("decision").asText();
        if (requestId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "requestId 必填"));
            return;
        }
        permissions.reply(requestId, RuntimeConfig.toReply(decision));
        publish(new LobsterEvent(Events.PERMISSION_REPLIED, null,
                on().put("requestId", requestId)
                        .put("decision", decision == null || decision.isEmpty() ? "REJECT" : decision), false));
        sendRes(id, true, on().put("requestId", requestId));
    }

    private void sessionsList(String id) {
        var list = store.listSessions();
        ArrayNode a = arr();
        for (var s : list) {
            a.add(on().put("id", s.id()).put("sessionKey", s.sessionKey())
                    .put("kind", s.kind())
                    .put("title", s.title() == null || s.title().isEmpty() ? s.sessionKey() : s.title())
                    .put("createdAt", s.createdAt()).put("updatedAt", s.updatedAt()));
        }
        sendRes(id, true, on().set("sessions", a));
    }
}
