package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.store.AuditStore;
import org.springframework.web.socket.WebSocketSession;

import java.util.function.BiConsumer;

/** 单次 RPC 请求的上下文（按连接/请求绑定），替代 WsHandler.sendRes / bus.publish / audit 等私有调用。 */
public class RpcContext {

    private final WebSocketSession session;
    private final ObjectMapper om;
    private final EventBus bus;
    private final AuditStore auditStore;
    private final BiConsumer<String, JsonNode> responder;

    public RpcContext(WebSocketSession session, ObjectMapper om, EventBus bus,
                      AuditStore auditStore, BiConsumer<String, JsonNode> responder) {
        this.session = session;
        this.om = om;
        this.bus = bus;
        this.auditStore = auditStore;
        this.responder = responder;
    }

    public WebSocketSession session() { return session; }
    public ObjectMapper om() { return om; }

    public void sendRes(String id, boolean ok, JsonNode payload) {
        var frame = om.createObjectNode();
        frame.put("type", "res");
        frame.put("id", id);
        frame.put("ok", ok);
        if (ok) frame.set("payload", payload);
        else frame.set("error", payload);
        responder.accept(id, frame);
    }

    public void ok(String id, JsonNode payload) { sendRes(id, true, payload); }

    public void fail(String id, String code, String message) {
        sendRes(id, false, om.createObjectNode().put("code", code).put("message", message));
    }

    public void publish(LobsterEvent e) { bus.publish(e); }

    public void audit(String kind, String sessionKey, String result, String meta) {
        if (auditStore != null) auditStore.record(null, kind, sessionKey, "main", result, meta);
    }
}
