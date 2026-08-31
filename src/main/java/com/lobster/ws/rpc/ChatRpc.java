package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.command.CommandExecutor;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.store.InboxStore;
import com.lobster.store.MessageStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

/** 聊天 RPC：chat.send（含 slash 命令路由与队列模式分流）/ chat.history。 */
@Component
public class ChatRpc extends BaseRpc {

    private final MessageStore store;
    private final AgentLoop loop;
    private final InboxStore inbox;
    private final CommandExecutor commandExecutor;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatRpc.class);

    public ChatRpc(MessageStore store, AgentLoop loop, InboxStore inbox, CommandExecutor commandExecutor) {
        this.store = store;
        this.loop = loop;
        this.inbox = inbox;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public Set<String> methods() { return Set.of("chat.send", "chat.history"); }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        if ("chat.send".equals(method)) chatSend(id, params);
        else if ("chat.history".equals(method)) chatHistory(id, params);
    }

    private void chatSend(String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        String text = params.path("text").asText();
        log.info("chat.send 收到 sessionKey={} textLen={} textHead={}", sessionKey, text.length(),
                text.length() > 80 ? text.substring(0, 80) + "..." : text);
        if (text.startsWith("/")) {
            var s = store.findByKey(sessionKey)
                    .orElseGet(() -> store.createSession(sessionKey, "main", System.getProperty("user.dir")));
            var r = commandExecutor.execute(text, s.id());
            log.info("chat.send 斜杠命令执行 ok={} outputLen={}", r.ok(), r.output() == null ? 0 : r.output().length());
            sendRes(id, r.ok(), on().put("status", r.ok() ? "done" : "error").put("output", r.output()));
            return;
        }
        var existing = store.findByKey(sessionKey);
        var s = existing.orElseGet(() -> store.createSession(sessionKey, "main", System.getProperty("user.dir")));
        if (loop.isBusy(s.id())) {
            log.info("chat.send 会话繁忙，进入队列分流 session={}", s.id());
            var disp = loop.queueMode().dispatch(s.id(), true,
                    ignore -> inbox.enqueue(s.id(), text),
                    () -> loop.requestAbort(s.id()));
            publish(new LobsterEvent("session.input.queued", s.id(),
                    on().put("text", text).put("mode", disp.mode().name().toLowerCase()).put("note", disp.note()), false));
        } else {
            log.info("chat.send 直接触发 agent run session={}", s.id());
            store.appendUser(s.id(), List.of(new Part.Text(text, false, false)));
            publish(new LobsterEvent(Events.PROMPT_ADMITTED, s.id(), on().put("text", text), true));
            Thread.ofVirtual().name("agent-loop-" + s.id()).start(() -> loop.run(s.id()));
        }
        sendRes(id, true, on().put("runId", s.id()).put("status", "started"));
    }

    private void chatHistory(String id, JsonNode params) {
        String sessionKey = params.path("sessionKey").asText("main");
        var s = store.findByKey(sessionKey);
        ObjectNode payload = on();
        ArrayNode messages = arr();
        if (s.isPresent()) {
            for (Message m : store.loadActive(s.get().id())) {
                ObjectNode node = on();
                node.put("id", m.id());
                node.put("role", m.role());
                node.put("createdAt", m.createdAt());
                ArrayNode parts = arr();
                if (m.parts() != null) for (Part p : m.parts()) parts.add(ctx.om().valueToTree(p));
                node.set("parts", parts);
                messages.add(node);
            }
        }
        payload.set("messages", messages);
        sendRes(id, true, payload);
    }
}
