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
    private final Map<WebSocketSession, Runnable> unsubscribes = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WsHandler(MessageStore store, EventBus bus, AgentLoop loop,
                     com.lobster.permission.PermissionEngine permissions,
                     com.lobster.store.InboxStore inbox,
                     com.lobster.rbac.AgentRegistry agents) {
        this.store = store;
        this.bus = bus;
        this.loop = loop;
        this.permissions = permissions;
        this.inbox = inbox;
        this.agents = agents;
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
            default -> {
                ObjectNode err = OM.createObjectNode();
                err.put("code", "METHOD_NOT_FOUND");
                err.put("message", "未知方法: " + method);
                sendRes(session, id, false, err);
            }
        }
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
            // busy：入收件箱（下轮 admit），立即 ack
            inbox.enqueue(s.id(), text);
            bus.publish(new LobsterEvent("session.input.queued", s.id(),
                    OM.createObjectNode().put("text", text), false));
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
