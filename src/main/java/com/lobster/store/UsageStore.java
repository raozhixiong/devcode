package com.lobster.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Usage 统计（对齐 FR-E-7/FR-F-6）：token/cost 按 agent/会话/日聚合。
 * 操作 agent 库 session 表的 tokens_input/tokens_output/cost 字段。
 */
public class UsageStore {

    private final JdbcTemplate jdbc;

    public UsageStore(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    public record AgentUsage(String agentId, long totalInput, long totalOutput,
                             double totalCost, int sessionCount) {}

    public record SessionUsage(String sessionId, String sessionKey, String agentId,
                               long tokensInput, long tokensOutput, double cost,
                               long createdAt, long updatedAt) {}

    public record DailyUsage(String date, long totalInput, long totalOutput,
                             double totalCost, int sessionCount) {}

    /** 按 agent 聚合 usage。 */
    public List<AgentUsage> usageByAgent() {
        return jdbc.query("""
                SELECT agent_id, COALESCE(SUM(tokens_input),0), COALESCE(SUM(tokens_output),0),
                       COALESCE(SUM(cost),0), COUNT(*)
                FROM session WHERE archived_at IS NULL
                GROUP BY agent_id ORDER BY COALESCE(SUM(cost),0) DESC
                """,
                (rs, i) -> new AgentUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getDouble(4), rs.getInt(5)));
    }

    /** 单 agent usage。 */
    public AgentUsage usageForAgent(String agentId) {
        List<AgentUsage> rows = jdbc.query("""
                SELECT agent_id, COALESCE(SUM(tokens_input),0), COALESCE(SUM(tokens_output),0),
                       COALESCE(SUM(cost),0), COUNT(*)
                FROM session WHERE archived_at IS NULL AND agent_id=?
                GROUP BY agent_id
                """,
                (rs, i) -> new AgentUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getDouble(4), rs.getInt(5)),
                agentId);
        return rows.isEmpty() ? new AgentUsage(agentId, 0, 0, 0, 0) : rows.get(0);
    }

    /** 会话列表含 usage。 */
    public List<SessionUsage> listSessions() {
        return jdbc.query("""
                SELECT id, session_key, agent_id, tokens_input, tokens_output, cost, created_at, updated_at
                FROM session WHERE archived_at IS NULL
                ORDER BY updated_at DESC
                """,
                (rs, i) -> new SessionUsage(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getDouble(6),
                        rs.getLong(7), rs.getLong(8)));
    }

    /** 单会话 usage。 */
    public SessionUsage sessionUsage(String sessionId) {
        List<SessionUsage> rows = jdbc.query("""
                SELECT id, session_key, agent_id, tokens_input, tokens_output, cost, created_at, updated_at
                FROM session WHERE id=?
                """,
                (rs, i) -> new SessionUsage(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getDouble(6),
                        rs.getLong(7), rs.getLong(8)),
                sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按日聚合（最近 N 天）。 */
    public List<DailyUsage> dailyUsage(int days) {
        long since = System.currentTimeMillis() - (long) days * 86400000L;
        return jdbc.query("""
                SELECT date(created_at/1000, 'unixepoch', 'localtime') as day,
                       COALESCE(SUM(tokens_input),0), COALESCE(SUM(tokens_output),0),
                       COALESCE(SUM(cost),0), COUNT(*)
                FROM session WHERE archived_at IS NULL AND created_at >= ?
                GROUP BY day ORDER BY day DESC
                """,
                (rs, i) -> new DailyUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getDouble(4), rs.getInt(5)),
                since);
    }

    /** 更新会话 usage（AgentLoop 每步调用）。 */
    public void accumulate(String sessionId, long inputTokens, long outputTokens, double cost) {
        jdbc.update("UPDATE session SET tokens_input=tokens_input+?, tokens_output=tokens_output+?, cost=cost+?, updated_at=? WHERE id=?",
                inputTokens, outputTokens, cost, System.currentTimeMillis(), sessionId);
    }
}
