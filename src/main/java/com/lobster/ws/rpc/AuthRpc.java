package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.auth.AuthInfo;
import com.lobster.auth.AuthService;
import com.lobster.ws.ConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 认证 / 引导 RPC（M5-27）。 */
@Component
public class AuthRpc extends BaseRpc {

    private final AuthService authService;
    private final ConnectionRegistry conn;

    public AuthRpc(AuthService authService, ConnectionRegistry conn) {
        this.authService = authService;
        this.conn = conn;
    }

    @Override
    public Set<String> methods() {
        return Set.of("connect", "auth.bootstrap", "auth.login", "auth.users.list", "auth.users.create", "auth.token.revoke");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "connect" -> connect(session, id, params);
            case "auth.bootstrap" -> bootstrap(id, params);
            case "auth.login" -> login(id, params);
            case "auth.users.list" -> usersList(id);
            case "auth.users.create" -> usersCreate(id, params);
            case "auth.token.revoke" -> tokenRevoke(id, params);
        }
    }

    private void connect(WebSocketSession session, String id, JsonNode params) {
        if (!authService.isAuthRequired()) {
            sendRes(id, true, on().put("protocol", 1).put("authRequired", false)
                    .set("policy", on().put("maxPayload", 1048576)));
            return;
        }
        String token = params.path("token").asText("");
        if (token.isEmpty()) {
            sendRes(id, false, on().put("code", "UNAUTHORIZED").put("message", "请提供 token"));
            return;
        }
        var authInfo = authService.validateToken(token);
        if (authInfo.isEmpty()) {
            sendRes(id, false, on().put("code", "UNAUTHORIZED").put("message", "令牌无效或已过期"));
            return;
        }
        conn.putAuth(session.getId(), authInfo.get());
        ObjectNode payload = on().put("protocol", 1).put("authRequired", true);
        ObjectNode auth = on().put("userId", authInfo.get().userId()).put("username", authInfo.get().username())
                .put("role", authInfo.get().role()).put("scopes", authInfo.get().scopes());
        payload.set("auth", auth);
        payload.set("policy", on().put("maxPayload", 1048576));
        sendRes(id, true, payload);
    }

    private void bootstrap(String id, JsonNode params) {
        if (authService.userCount() > 0) {
            sendRes(id, false, on().put("code", "CONFLICT").put("message", "系统已初始化"));
            return;
        }
        String username = params.path("username").asText();
        String displayName = params.path("displayName").asText(username);
        String password = params.path("password").asText();
        String role = params.path("role").asText("admin");
        if (username.isEmpty() || password.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "username 和 password 必填"));
            return;
        }
        var result = authService.bootstrap(username, displayName, password, role);
        audit("auth.bootstrap", null, "success", on().put("userId", result.user().id())
                .put("username", result.user().username()).put("role", result.user().role()).toString());
        authService.validateToken(result.token()).ifPresent(info -> conn.putAuth(session.getId(), info));
        sendRes(id, true, on().put("token", result.token()).put("userId", result.user().id())
                .put("username", result.user().username()).put("role", result.user().role()));
    }

    private void login(String id, JsonNode params) {
        String username = params.path("username").asText();
        String password = params.path("password").asText();
        if (username.isEmpty() || password.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "username 和 password 必填"));
            return;
        }
        try {
            var result = authService.login(username, password);
            audit("auth.login", null, "success", on().put("userId", result.user().id())
                    .put("username", result.user().username()).toString());
            authService.validateToken(result.token()).ifPresent(info -> conn.putAuth(session.getId(), info));
            sendRes(id, true, on().put("token", result.token()).put("userId", result.user().id())
                    .put("username", result.user().username()).put("role", result.user().role()));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "UNAUTHORIZED").put("message", e.getMessage()));
        }
    }

    private void usersList(String id) {
        ArrayNode arr = (ArrayNode) ctx.om().valueToTree(authService.users().list());
        sendRes(id, true, on().set("users", arr));
    }

    private void usersCreate(String id, JsonNode params) {
        String username = params.path("username").asText();
        String displayName = params.path("displayName").asText(username);
        String email = params.path("email").asText(null);
        String password = params.path("password").asText();
        String role = params.path("role").asText("developer");
        if (username.isEmpty() || password.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "username 和 password 必填"));
            return;
        }
        try {
            var user = authService.createUser(username, displayName, email, password, role);
            sendRes(id, true, on().put("userId", user.id()).put("username", user.username()).put("role", user.role()));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "CONFLICT").put("message", e.getMessage()));
        }
    }

    private void tokenRevoke(String id, JsonNode params) {
        String tokenId = params.path("tokenId").asText();
        if (tokenId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "tokenId 必填"));
            return;
        }
        authService.revokeToken(tokenId);
        audit("auth.token.revoke", null, "success", on().put("tokenId", tokenId).toString());
        sendRes(id, true, on().put("tokenId", tokenId));
    }
}
