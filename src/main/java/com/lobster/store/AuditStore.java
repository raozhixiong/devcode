package com.lobster.store;

import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/** 审计台账（对齐 FR-G-5）：metadata-only，30 天保留，10 万行上限。 */
public class AuditStore {

    private static final int MAX_ROWS = 100_000;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private final JdbcTemplate jdbc;

    public AuditStore(JdbcTemplate sharedJdbc) {
        this.jdbc = sharedJdbc;
    }

    public record AuditEvent(String id, long ts, String actor, String kind,
                             String sessionKey, String agentId,
                             String result, String meta) {}

    public void record(String actor, String kind, String sessionKey,
                       String agentId, String result, String meta) {
        String id = Ulid.next("aud_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO audit_event(id, ts, actor, kind, session_key, agent_id, result, meta)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, now, actor, kind, sessionKey, agentId, result, meta);
        enforceLimits();
    }

    public List<AuditEvent> list(String kindFilter, int limit, Long beforeTs) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        long ts = beforeTs == null ? System.currentTimeMillis() + 1 : beforeTs;
        if (kindFilter == null || kindFilter.isEmpty()) {
            return jdbc.query("""
                    SELECT id, ts, actor, kind, session_key, agent_id, result, meta
                    FROM audit_event WHERE ts < ? ORDER BY ts DESC LIMIT ?
                    """, AuditStore::map, ts, safeLimit);
        }
        return jdbc.query("""
                SELECT id, ts, actor, kind, session_key, agent_id, result, meta
                FROM audit_event WHERE ts < ? AND kind = ? ORDER BY ts DESC LIMIT ?
                """, AuditStore::map, ts, kindFilter, safeLimit);
    }

    public Optional<AuditEvent> findById(String id) {
        return jdbc.query("""
                SELECT id, ts, actor, kind, session_key, agent_id, result, meta
                FROM audit_event WHERE id = ?
                """, AuditStore::map, id).stream().findFirst();
    }

    public List<AuditEvent> listBySession(String sessionKey, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                SELECT id, ts, actor, kind, session_key, agent_id, result, meta
                FROM audit_event WHERE session_key = ? ORDER BY ts DESC LIMIT ?
                """, AuditStore::map, sessionKey, safeLimit);
    }

    public List<AuditEvent> listByActor(String actor, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                SELECT id, ts, actor, kind, session_key, agent_id, result, meta
                FROM audit_event WHERE actor = ? ORDER BY ts DESC LIMIT ?
                """, AuditStore::map, actor, safeLimit);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
        return n == null ? 0 : n;
    }

    public int cleanup(long beforeTs) {
        return jdbc.update("DELETE FROM audit_event WHERE ts < ?", beforeTs);
    }

    private void enforceLimits() {
        int total = count();
        if (total > MAX_ROWS) {
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            cleanup(cutoff);
            total = count();
            if (total > MAX_ROWS) {
                jdbc.update("DELETE FROM audit_event WHERE id IN " +
                        "(SELECT id FROM audit_event ORDER BY ts ASC LIMIT ?)",
                        total - MAX_ROWS);
            }
        }
    }

    private static AuditEvent map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AuditEvent(
                rs.getString("id"), rs.getLong("ts"),
                rs.getString("actor"), rs.getString("kind"),
                rs.getString("session_key"), rs.getString("agent_id"),
                rs.getString("result"), rs.getString("meta"));
    }
}
