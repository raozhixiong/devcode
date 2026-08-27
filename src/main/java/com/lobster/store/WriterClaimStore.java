package com.lobster.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Writer claim 围栏（session_active_writer 表）：
 * run 开始 claim（sessionKey + runId + 随机 generation），写前校验，结束释放。
 * 防止多写入方并发覆盖同一会话（乐观围栏：代际不匹配即拒绝）。
 */
public class WriterClaimStore {

    private final JdbcTemplate jdbc;

    public WriterClaimStore(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    public record Claim(String sessionKey, String runId, String generation) {}

    /** claim 会话写入权。已被持有且未过期 -> 返回空（写入方应排队或失败）。 */
    public Claim claim(String sessionKey, String runId) {
        // 先查（SQLite 主键冲突异常类型不稳定，避免依赖异常翻译）
        try {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM session_active_writer WHERE session_key=?", Integer.class, sessionKey);
            if (existing != null && existing > 0) return null; // 已被其他 run 持有
        } catch (Exception ignored) {}
        String generation = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO session_active_writer(session_key, run_id, generation, claimed_at) VALUES(?,?,?,?)",
                    sessionKey, runId, generation, System.currentTimeMillis());
        } catch (Exception e) {
            return null; // 并发插入冲突
        }
        return new Claim(sessionKey, runId, generation);
    }

    /** 校验 claim 仍归当前 run（写前调用）。 */
    public boolean validate(Claim claim) {
        if (claim == null) return false;
        try {
            String gen = jdbc.queryForObject(
                    "SELECT generation FROM session_active_writer WHERE session_key=? AND run_id=?",
                    String.class, claim.sessionKey(), claim.runId());
            return claim.generation().equals(gen);
        } catch (Exception e) {
            return false;
        }
    }

    /** 释放 claim（run 结束/中止）。 */
    public void release(Claim claim) {
        if (claim == null) return;
        jdbc.update("DELETE FROM session_active_writer WHERE session_key=? AND run_id=? AND generation=?",
                claim.sessionKey(), claim.runId(), claim.generation());
    }

    /** 崩溃恢复：清理所有孤儿 claim（重启时调用，返回清理条数）。 */
    public int clearOrphans() {
        return jdbc.update("DELETE FROM session_active_writer");
    }
}
