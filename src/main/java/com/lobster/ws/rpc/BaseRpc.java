package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.LobsterEvent;
import org.springframework.web.socket.WebSocketSession;

/** RPC 处理器基类：持有每请求 RpcContext，提供 sendRes/audit/publish/on/arr 等便捷方法。 */
public abstract class BaseRpc implements RpcHandler {

    protected RpcContext ctx;
    protected WebSocketSession session;

    protected ObjectNode on() { return ctx.om().createObjectNode(); }
    protected ArrayNode arr() { return ctx.om().createArrayNode(); }
    protected void sendRes(String id, boolean ok, JsonNode payload) { ctx.sendRes(id, ok, payload); }
    protected void fail(String id, String code, String message) { ctx.fail(id, code, message); }
    protected void audit(String kind, String sessionKey, String result, String meta) { ctx.audit(kind, sessionKey, result, meta); }
    protected void publish(LobsterEvent e) { ctx.publish(e); }
}
