package com.lobster.store;

import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 三层归属管理（对齐 FR-B1-5）：
 * - Creator：创建者（不可变，createSession 时记录）
 * - Owner：当前负责人（可指派）
 * - Participants：参与历史（≤32 条）
 */
public class SessionOwnership {

    private final JdbcTemplate jdbc;
    private static final int MAX_PARTICIPANTS = 32;

    public SessionOwnership(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    /** 会话创建时设置 creator 与初始 owner。 */
    public void setCreator(String sessionId, String actor) {
        jdbc.update("UPDATE session SET created_actor=?, owner=? WHERE id=?", actor, actor, sessionId);
        addParticipant(sessionId, actor);
    }

    /** 指派新 owner（记录到 participants）。 */
    public void assignOwner(String sessionId, String actor) {
        jdbc.update("UPDATE session SET owner=?, updated_at=? WHERE id=?",
                actor, System.currentTimeMillis(), sessionId);
        addParticipant(sessionId, actor);
    }

    /** 添加参与记录（去重 upsert，超过 32 条移除最旧）。 */
    public void addParticipant(String sessionId, String actor) {
        jdbc.update("""
                INSERT INTO session_participant(session_id, actor_type, actor_id, last_at)
                VALUES(?, 'user', ?, ?)
                ON CONFLICT(session_id, actor_type, actor_id) DO UPDATE SET last_at=excluded.last_at
                """, sessionId, actor, System.currentTimeMillis());
        // 淘汰：超过 MAX_PARTICIPANTS 移除最旧
        jdbc.update("""
                DELETE FROM session_participant WHERE session_id=?
                AND (session_id, actor_type, actor_id) IN (
                    SELECT session_id, actor_type, actor_id FROM session_participant
                    WHERE session_id=? ORDER BY last_at DESC LIMIT -1 OFFSET ?
                )
                """, sessionId, sessionId, MAX_PARTICIPANTS);
    }

    public record Participant(String actorId, long lastAt) {}

    public List<Participant> listParticipants(String sessionId) {
        return jdbc.query(
                "SELECT actor_id, last_at FROM session_participant WHERE session_id=? ORDER BY last_at DESC",
                (rs, i) -> new Participant(rs.getString(1), rs.getLong(2)),
                sessionId);
    }

    public String owner(String sessionId) {
        List<String> rows = jdbc.query(
                "SELECT owner FROM session WHERE id=?", (rs, i) -> rs.getString(1), sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String creator(String sessionId) {
        List<String> rows = jdbc.query(
                "SELECT created_actor FROM session WHERE id=?", (rs, i) -> rs.getString(1), sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 owner 过滤会话列表。 */
    public List<String> listByOwner(String owner) {
        return jdbc.query(
                "SELECT id FROM session WHERE owner=? AND archived_at IS NULL ORDER BY updated_at DESC",
                (rs, i) -> rs.getString(1), owner);
    }
}
