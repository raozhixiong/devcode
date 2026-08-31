package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.auth.AuthService;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.store.AuditStore;
import com.lobster.ws.rpc.RpcContext;
import com.lobster.ws.rpc.RpcHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** WS 帧协议处理器：req/res/event。仅负责传输、鉴权闸门与 RPC 分发表（具体逻辑见 com.lobster.ws.rpc）。 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private static final Set<String> AUTH_ALLOWED_METHODS =
            Set.of("connect", "auth.bootstrap", "auth.login", "audit.activity.list");

    private final EventBus bus;
    private final AuthService authService;
    private final ConnectionRegistry conn;
    private final AuditStore auditStore;
    private final Map<String, RpcHandler> registry = new ConcurrentHashMap<>();

    public WsHandler(EventBus bus, AuthService authService, ConnectionRegistry conn,
                     AuditStore auditStore, List<RpcHandler> handlers) {
        this.bus = bus;
        this.authService = authService;
        this.conn = conn;
        this.auditStore = auditStore;
        for (var h : handlers) {
            for (var m : h.methods()) registry.put(m, h);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        conn.putSession(session.getId(), session);
        Runnable unsub = bus.subscribeAll(e -> sendEvent(session, e));
        conn.putUnsub(session.getId(), unsub);
        log.info("WS 连接建立 session={}", session.getId());
        if (authService.isAuthRequired()) {
            sendEvent(session, new LobsterEvent(Events.CONNECT_CHALLENGE, "",
                    OM.createObjectNode()
                            .put("authRequired", true)
                            .put("nonce", java.util.UUID.randomUUID().toString())
                            .put("ts", System.currentTimeMillis()), false));
        } else {
            log.info("WS 连接无需认证 session={}", session.getId());
            sendRes(session, "connect", true, OM.createObjectNode()
                    .put("protocol", 1).put("authRequired", false)
                    .set("policy", OM.createObjectNode().put("maxPayload", 1048576)));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WS 连接关闭 session={} status={}", session.getId(), status);
        conn.removeSession(session.getId());
        Runnable unsub = conn.takeUnsub(session.getId());
        if (unsub != null) unsub.run();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame = OM.readTree(message.getPayload());
        if (!"req".equals(frame.path("type").asText())) return;
        String id = frame.path("id").asText();
        String method = frame.path("method").asText();
        JsonNode params = frame.path("params");
        log.info("WS 收到请求 session={} id={} method={}", session.getId(), id, method);
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
        if (authService.isAuthRequired() && !conn.isAuthed(session.getId())
                && !AUTH_ALLOWED_METHODS.contains(method)) {
            log.warn("WS 未认证请求被拒 session={} method={}", session.getId(), method);
            ObjectNode err = OM.createObjectNode();
            err.put("code", "UNAUTHORIZED").put("message", "请先认证");
            sendRes(session, id, false, err);
            return;
        }
        RpcHandler handler = registry.get(method);
        if (handler == null) {
            log.warn("WS 未知方法 session={} method={}", session.getId(), method);
            ObjectNode err = OM.createObjectNode();
            err.put("code", "METHOD_NOT_FOUND").put("message", "未知方法: " + method);
            sendRes(session, id, false, err);
            return;
        }
        BiConsumer<String, JsonNode> responder = (rid, frame) -> send(session, frame);
        RpcContext ctx = new RpcContext(session, OM, bus, auditStore, responder);
        handler.handle(session, id, method, params, ctx);
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
