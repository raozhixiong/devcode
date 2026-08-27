package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.model.Session;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 会话/消息/Part 持久化。Part 以 JSON 存于 part.data。 */
public class MessageStore {

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MessageStore(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    public Session createSession(String sessionKey, String kind, String directory) {
        String id = Ulid.next("ses_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO session(id, session_key, kind, directory, created_at, updated_at) VALUES(?,?,?,?,?,?)",
                id, sessionKey, kind, directory, now, now);
        return new Session(id, sessionKey, kind, null, directory, now, now);
    }

    public Optional<Session> findByKey(String sessionKey) {
        List<Session> rows = jdbc.query(
                "SELECT id, session_key, kind, title, directory, created_at, updated_at FROM session WHERE session_key=? AND archived_at IS NULL",
                (rs, i) -> new Session(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)),
                sessionKey);
        return rows.stream().findFirst();
    }

    public Message appendUser(String sessionId, List<Part> parts) {
        return append(sessionId, "user", parts);
    }

    public Message appendAssistant(String sessionId) {
        return append(sessionId, "assistant", List.of());
    }

    private Message append(String sessionId, String role, List<Part> parts) {
        String id = Ulid.next("msg_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO message(id, session_id, role, data, created_at, updated_at) VALUES(?,?,?,?,?,?)",
                id, sessionId, role, "{}", now, now);
        for (Part p : parts) addPart(id, p);
        jdbc.update("UPDATE session SET updated_at=? WHERE id=?", now, sessionId);
        return new Message(id, sessionId, role, parts, now);
    }

    public void addPart(String messageId, Part part) {
        String id = Ulid.next("prt_");
        try {
            String type = part instanceof Part.Text ? "text"
                    : part instanceof Part.Reasoning ? "reasoning"
                    : part instanceof Part.Tool ? "tool"
                    : part instanceof Part.File ? "file"
                    : part instanceof Part.StepFinish ? "step-finish"
                    : part instanceof Part.Snapshot ? "snapshot"
                    : part instanceof Part.Compaction ? "compaction"
                    : "synthetic";
            String json = OM.writeValueAsString(part);
            jdbc.update("INSERT INTO part(id, session_id, message_id, type, data, created_at) " +
                            "SELECT ?, session_id, ?, ?, ?, ? FROM message WHERE id=?",
                    id, messageId, type, json, System.currentTimeMillis(), messageId);
        } catch (Exception e) {
            throw new IllegalStateException("part 序列化失败", e);
        }
    }

    /** 更新指定消息中 callId 的工具状态（读-改-写整条 part 行）。 */
    public void updateToolState(String messageId, String callId, Part.ToolState state) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, data FROM part WHERE message_id=? AND type='tool'", messageId);
        for (Map<String, Object> row : rows) {
            try {
                Part.Tool tool = OM.readValue((String) row.get("data"), Part.Tool.class);
                if (!tool.callId().equals(callId)) continue;
                Part.Tool updated = new Part.Tool(tool.tool(), tool.callId(), state);
                jdbc.update("UPDATE part SET data=? WHERE id=?",
                        OM.writeValueAsString(updated), row.get("id"));
                return;
            } catch (Exception e) {
                throw new IllegalStateException("tool part 更新失败", e);
            }
        }
        throw new IllegalArgumentException("tool part 未找到: " + callId);
    }

    /** 压缩：把 sessionId 中截至 keepFromId（不含）的历史标记为已压缩，并写入一条 compaction 摘要消息（新纪元起点）。 */
    public Message compact(String sessionId, String keepFromId, String summary) {
        long now = System.currentTimeMillis();
        // Java 侧定位 cutoff（避免 SQLite 行元组比较差异），cutoff 之前的消息打 compacted 标记
        List<String> all = jdbc.query(
                "SELECT id FROM message WHERE session_id=? ORDER BY created_at, id",
                (rs, i) -> rs.getString(1), sessionId);
        boolean found = keepFromId == null;
        for (String id : all) {
            if (keepFromId != null && id.equals(keepFromId)) { found = true; break; }
            jdbc.update("UPDATE message SET data=json_set(data, '$.compacted', json('true')), updated_at=? WHERE id=?",
                    now, id);
        }
        if (!found) throw new IllegalArgumentException("keepFromId 不在会话中: " + keepFromId);        // compaction 摘要作为新 user 消息（纪元起点，loadActive 会从它开始）
        var msg = append(sessionId, "user", List.of(new Part.Compaction(true, summary)));
        jdbc.update("UPDATE session SET compaction_baseline=?, updated_at=? WHERE id=?",
                msg.id(), now, sessionId);
        return msg;
    }

    public List<Message> loadActive(String sessionId) {
        List<Message> messages = jdbc.query(
                "SELECT id FROM message WHERE session_id=? ORDER BY created_at, id",
                (rs, i) -> rs.getString(1), sessionId).stream()
                .map(mid -> new Message(mid, sessionId, null, null, 0))
                .collect(Collectors.toList());
        return messages.stream().map(m -> loadMessage(m.id()))
                .filter(m -> !isCompacted(m))
                .collect(Collectors.toList());
    }

    /** 消息是否已被压缩（data.compacted 标记或含 Compaction part 之前的历史）。 */
    private boolean isCompacted(Message m) {
        try {
            String data = jdbc.queryForObject(
                    "SELECT data FROM message WHERE id=?", String.class, m.id());
            return data != null && data.contains("\"compacted\":true");
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<Message> lastMessage(String sessionId) {
        List<String> ids = jdbc.query(
                "SELECT id FROM message WHERE session_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), sessionId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(loadMessage(ids.get(0)));
    }

    private Message loadMessage(String messageId) {
        record Row(String id, String sessionId, String role, long createdAt) {}
        Row row = jdbc.queryForObject(
                "SELECT id, session_id, role, created_at FROM message WHERE id=?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                messageId);
        List<Part> parts = jdbc.query(
                "SELECT data FROM part WHERE message_id=? ORDER BY id",
                (rs, i) -> deserialize(rs.getString(1)), messageId);
        return new Message(row.id(), row.sessionId(), row.role(), parts, row.createdAt());
    }

    private Part deserialize(String json) {
        try {
            return OM.readValue(json, Part.class);
        } catch (Exception e) {
            throw new IllegalStateException("part 反序列化失败", e);
        }
    }
}
