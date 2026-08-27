package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 状态感知（对齐 FR-B1-7）：
 * - stateVersion 乐观锁：每次会话变更 bump version
 * - changesSince：返回该版本之后的信号
 * - watcher：spawn 父自动订阅子会话状态变化（M3 通过 session_state_signal 表 + 事件推送）
 */
public class SessionStateService {

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final com.lobster.event.EventBus bus;

    public SessionStateService(AgentDb db, com.lobster.event.EventBus bus) {
        this.jdbc = db.jdbc();
        this.bus = bus;
    }

    /** 获取当前 stateVersion。 */
    public long getVersion(String sessionId) {
        List<Long> rows = jdbc.query(
                "SELECT state_version FROM session WHERE id=?", (rs, i) -> rs.getLong(1), sessionId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * bump version 并记录信号。
     * @return 新 version
     */
    public long bump(String sessionId, String kind, ObjectNode payload) {
        long newVersion = getVersion(sessionId) + 1;
        long now = System.currentTimeMillis();
        String signalId = "sig_" + Long.toHexString(now) + Long.toHexString(System.nanoTime());
        String sessionKey = jdbc.queryForObject(
                "SELECT session_key FROM session WHERE id=?", String.class, sessionId);
        jdbc.update("INSERT INTO session_state_signal(id, session_key, state_version, kind, payload, created_at) " +
                        "VALUES(?,?,?,?,?,?)",
                signalId, sessionKey, newVersion,
                kind, payload == null ? "{}" : payload.toString(), now);
        jdbc.update("UPDATE session SET state_version=?, updated_at=? WHERE id=?",
                newVersion, now, sessionId);
        // 推送状态变更事件
        ObjectNode evtData = OM.createObjectNode().put("stateVersion", newVersion).put("kind", kind);
        if (payload != null) evtData.set("payload", payload);
        bus.publish(new com.lobster.event.LobsterEvent(
                com.lobster.event.Events.SESSION_STATE_CHANGED, sessionId, evtData, true));
        return newVersion;
    }

    public record Signal(long stateVersion, String kind, String payload, long createdAt) {}

    /** 返回 sinceVersion 之后的信号（不含等于 since 的）。 */
    public List<Signal> changesSince(String sessionId, long sinceVersion) {
        return jdbc.query("""
                SELECT s.state_version, s.kind, s.payload, s.created_at
                FROM session_state_signal s
                JOIN session sess ON sess.session_key = s.session_key
                WHERE sess.id=? AND s.state_version > ?
                ORDER BY s.state_version
                """,
                (rs, i) -> new Signal(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                sessionId, sinceVersion);
    }
}
