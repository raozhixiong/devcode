package com.lobster.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.store.MessageStore;
import com.lobster.util.Ulid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Agent Client Protocol（ACP）最小实现（FR-I7）：HTTP 控制面驱动代理。 */
@RestController
@RequestMapping("/acp/v1")
public class AcpController {

    private static final ObjectMapper OM = new ObjectMapper();
    private final AgentLoop agentLoop;
    private final MessageStore messageStore;

    public AcpController(AgentLoop agentLoop, MessageStore messageStore) {
        this.agentLoop = agentLoop;
        this.messageStore = messageStore;
    }

    @GetMapping("/agents")
    public ObjectNode agents() {
        ObjectNode r = OM.createObjectNode();
        r.set("agents", OM.createArrayNode().add("main"));
        return r;
    }

    @PostMapping("/sessions")
    public ObjectNode createSession(@RequestBody Map<String, Object> body) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        String sessionKey = "acp_" + Ulid.next("acp_");
        var sess = messageStore.createSession(sessionKey, "conversation", System.getProperty("user.dir"));
        messageStore.appendUser(sess.id(), List.of(new Part.Text(message, false, false)));
        agentLoop.run(sess.id());
        ObjectNode r = OM.createObjectNode();
        r.put("sessionKey", sessionKey).put("status", "queued");
        return r;
    }

    @GetMapping("/sessions/{sessionKey}")
    public ObjectNode getSession(@PathVariable String sessionKey) {
        var sess = messageStore.findByKey(sessionKey).orElse(null);
        if (sess == null) {
            ObjectNode r = OM.createObjectNode();
            r.put("sessionKey", sessionKey).set("messages", OM.createArrayNode());
            return r;
        }
        List<Message> msgs = messageStore.loadActive(sess.id());
        ArrayNode arr = OM.createArrayNode();
        for (var m : msgs) {
            ObjectNode o = OM.createObjectNode();
            o.put("role", m.role());
            o.set("parts", OM.valueToTree(m.parts()));
            arr.add(o);
        }
        ObjectNode r = OM.createObjectNode();
        r.put("sessionKey", sessionKey).set("messages", arr);
        return r;
    }
}
