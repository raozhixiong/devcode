package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 自注册 RPC 处理器：每个领域一组，按 method 名路由。 */
public interface RpcHandler {

    /** 该处理器负责的 method 名集合（用于注册到分发表）。 */
    Set<String> methods();

    void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception;
}
