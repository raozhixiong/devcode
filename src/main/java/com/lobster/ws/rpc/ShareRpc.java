package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.sandbox.WorktreeService;
import com.lobster.store.MessageStore;
import com.lobster.store.ShareService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Path;
import java.util.Set;

/** 分享链接 + 托管工作树 RPC（M6-32 / M6-33）。 */
@Component
public class ShareRpc extends BaseRpc {

    private final ShareService shareService;
    private final WorktreeService worktreeService;
    private final MessageStore store;

    public ShareRpc(ShareService shareService, WorktreeService worktreeService, MessageStore store) {
        this.shareService = shareService;
        this.worktreeService = worktreeService;
        this.store = store;
    }

    @Override
    public Set<String> methods() { return Set.of("share.create", "share.open", "worktree.create"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "share.create" -> shareCreate(id, params);
            case "share.open" -> shareOpen(id, params);
            case "worktree.create" -> worktreeCreate(id, params);
        }
    }

    private void shareCreate(String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText();
        if (sessionKey.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "sessionKey 必填"));
            return;
        }
        var sess = store.findByKey(sessionKey).orElse(null);
        if (sess == null) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "会话不存在"));
            return;
        }
        String token = shareService.create(sess.id());
        ObjectNode res = on();
        res.put("token", token).put("url", "/share/" + token).put("sessionId", sess.id());
        sendRes(id, true, res);
    }

    private void shareOpen(String id, JsonNode params) {
        String token = params.path("token").asText();
        if (token.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "token 必填"));
            return;
        }
        ObjectNode res = on();
        res.put("sessionId", shareService.sessionIdOf(token));
        res.set("messages", shareService.exportMessages(token));
        sendRes(id, true, res);
    }

    private void worktreeCreate(String id, JsonNode params) {
        String agentId = params.path("agentId").asText();
        if (agentId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "agentId 必填"));
            return;
        }
        try {
            Path p = worktreeService.create(agentId);
            sendRes(id, true, on().put("agentId", agentId).put("path", p.toString()));
        } catch (Exception e) {
            sendRes(id, false, on().put("code", "WORKTREE_ERROR").put("message", e.getMessage()));
        }
    }
}
