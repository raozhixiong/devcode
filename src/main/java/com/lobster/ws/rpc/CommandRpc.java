package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.command.CommandExecutor;
import com.lobster.command.CommandRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 命令注册 RPC（M6-30 / FR-I2）。 */
@Component
public class CommandRpc extends BaseRpc {

    private final CommandRegistry commands;
    private final CommandExecutor commandExecutor;

    public CommandRpc(CommandRegistry commands, CommandExecutor commandExecutor) {
        this.commands = commands;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public Set<String> methods() { return Set.of("command.list", "command.run"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        if ("command.list".equals(method)) list(id);
        else if ("command.run".equals(method)) run(id, params);
    }

    private void list(String id) {
        sendRes(id, true, on().set("commands", (ArrayNode) ctx.om().valueToTree(commands.list())));
    }

    private void run(String id, JsonNode params) {
        String slash = params.path("slash").asText();
        String sessionId = params.path("sessionId").asText();
        if (sessionId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "sessionId 必填"));
            return;
        }
        var r = commandExecutor.execute(slash, sessionId);
        sendRes(id, r.ok(), on().put("status", r.ok() ? "done" : "error").put("output", r.output()));
    }
}
