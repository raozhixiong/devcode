package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 分享链接 Share（FR-I7）：为会话生成只读分享令牌与导出。 */
public class ShareService {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final MessageStore messages;
    private final EventBus bus;

    public ShareService(JdbcTemplate sharedJdbc, MessageStore messages, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.messages = messages;
        this.bus = bus;
    }

    public record Share(String id, String sessionKey, String token, long createdAt, Long expiresAt) {}

    public String create(String sessionId) {
        String token = Ulid.next("shr_");
        String id = Ulid.next("sh_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO shares(id, session_id, token, created_at, expires_at) VALUES(?,?,?,?,?)",
                id, sessionId, token, now, null);
        publish();
        return token;
    }

    public String sessionIdOf(String token) {
        return jdbc.queryForObject("SELECT session_id FROM shares WHERE token=?",
                (rs, i) -> rs.getString(1), token);
    }

    /** 只读导出会话消息（不含工具内部细节之外的敏感信息）。 */
    public ArrayNode exportMessages(String token) {
        String sk = sessionIdOf(token);
        if (sk == null) return OM.createArrayNode();
        List<Message> msgs = messages.loadActive(sk);
        ArrayNode arr = OM.createArrayNode();
        for (var m : msgs) {
            ObjectNode o = OM.createObjectNode();
            o.put("role", m.role());
            o.set("parts", OM.valueToTree(m.parts()));
            arr.add(o);
        }
        return arr;
    }

    private void publish() {
        ObjectNode data = OM.createObjectNode();
        bus.publish(new LobsterEvent(Events.SHARE_CHANGED, "", data, false));
    }
}
