package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 审批中心（对齐 FR-A5-5）：通用审批注册表 + exec 策略快照 + waitDecision。 */
public class ApprovalStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;
    private final ConcurrentHashMap<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    public ApprovalStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public enum Kind { EXEC, PERMISSION, PLUGIN, SYSTEM }

    public record Approval(String id, String kind, String sessionKey, String agentId,
                           String requester, String payload, String status,
                           String resolver, String reason,
                           long createdAt, Long resolvedAt) {}

    public record Decision(boolean approved, String resolver, String reason) {}

    public Approval create(String kind, String sessionKey, String agentId,
                           String requester, String payload) {
        String id = Ulid.next("apv_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO approval(id, kind, session_key, agent_id, requester, payload, status, created_at)
                VALUES(?,?,?,?,?,?,'pending',?)
                """, id, kind, sessionKey, agentId, requester, payload, now);
        pending.put(id, new CompletableFuture<>());
        ObjectNode data = OM.createObjectNode()
                .put("approvalId", id)
                .put("kind", kind)
                .put("requester", requester == null ? "" : requester);
        if (sessionKey != null) data.put("sessionKey", sessionKey);
        bus.publish(new LobsterEvent(Events.APPROVAL_REQUESTED, "", data, false));
        return new Approval(id, kind, sessionKey, agentId, requester, payload,
                "pending", null, null, now, null);
    }

    public Optional<Approval> get(String id) {
        return jdbc.query("""
                SELECT id, kind, session_key, agent_id, requester, payload, status,
                       resolver, reason, created_at, resolved_at
                FROM approval WHERE id = ?
                """, ApprovalStore::map, id).stream().findFirst();
    }

    public List<Approval> list(String kindFilter, String statusFilter) {
        boolean hasKind = kindFilter != null && !kindFilter.isEmpty();
        boolean hasStatus = statusFilter != null && !statusFilter.isEmpty();
        if (hasKind && hasStatus) {
            return jdbc.query(mapSql + " WHERE kind = ? AND status = ? ORDER BY created_at DESC LIMIT 200",
                    ApprovalStore::map, kindFilter, statusFilter);
        } else if (hasKind) {
            return jdbc.query(mapSql + " WHERE kind = ? ORDER BY created_at DESC LIMIT 200",
                    ApprovalStore::map, kindFilter);
        } else if (hasStatus) {
            return jdbc.query(mapSql + " WHERE status = ? ORDER BY created_at DESC LIMIT 200",
                    ApprovalStore::map, statusFilter);
        }
        return jdbc.query(mapSql + " ORDER BY created_at DESC LIMIT 200", ApprovalStore::map);
    }

    public List<Approval> history(String kindFilter, Long beforeTs, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        long ts = beforeTs == null ? System.currentTimeMillis() + 1 : beforeTs;
        if (kindFilter == null || kindFilter.isEmpty()) {
            return jdbc.query(mapSql + " WHERE created_at < ? ORDER BY created_at DESC LIMIT ?",
                    ApprovalStore::map, ts, safeLimit);
        }
        return jdbc.query(mapSql + " WHERE created_at < ? AND kind = ? ORDER BY created_at DESC LIMIT ?",
                ApprovalStore::map, ts, kindFilter, safeLimit);
    }

    public Optional<Decision> resolve(String id, String resolver, boolean approved, String reason) {
        var approval = get(id).orElse(null);
        if (approval == null || !"pending".equals(approval.status())) return Optional.empty();
        long now = System.currentTimeMillis();
        String status = approved ? "approved" : "rejected";
        jdbc.update("UPDATE approval SET status=?, resolver=?, reason=?, resolved_at=? WHERE id=?",
                status, resolver, reason, now, id);
        var decision = new Decision(approved, resolver, reason);
        var future = pending.remove(id);
        if (future != null) future.complete(decision);
        ObjectNode data = OM.createObjectNode()
                .put("approvalId", id)
                .put("status", status)
                .put("resolver", resolver == null ? "" : resolver);
        bus.publish(new LobsterEvent(Events.APPROVAL_RESOLVED, "", data, false));
        return Optional.of(decision);
    }

    public Decision waitDecision(String id, long timeoutMs) throws InterruptedException {
        var future = pending.get(id);
        if (future == null) {
            var existing = get(id).orElse(null);
            if (existing != null && !"pending".equals(existing.status())) {
                return new Decision("approved".equals(existing.status()),
                        existing.resolver(), existing.reason());
            }
            throw new IllegalArgumentException("审批请求不存在: " + id);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new Decision(false, null, "超时未决议");
        }
    }

    // ==================== exec 策略 ====================

    public String getPolicy(String scope) {
        List<String> results = jdbc.query(
                "SELECT policy FROM exec_approval_policy WHERE scope = ?",
                (rs, i) -> rs.getString("policy"), scope);
        return results.isEmpty() ? null : results.get(0);
    }

    public void setPolicy(String scope, String policy, String updatedBy) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("UPDATE exec_approval_policy SET policy=?, updated_by=?, updated_at=? WHERE scope=?",
                policy, updatedBy, now, scope);
        if (updated == 0) {
            jdbc.update("INSERT INTO exec_approval_policy(scope, policy, updated_by, updated_at) VALUES(?,?,?,?)",
                    scope, policy, updatedBy, now);
        }
    }

    // ==================== helpers ====================

    private static final String mapSql = """
            SELECT id, kind, session_key, agent_id, requester, payload, status,
                   resolver, reason, created_at, resolved_at
            FROM approval""";

    private static Approval map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Approval(
                rs.getString("id"), rs.getString("kind"),
                rs.getString("session_key"), rs.getString("agent_id"),
                rs.getString("requester"), rs.getString("payload"),
                rs.getString("status"), rs.getString("resolver"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                nLong(rs, "resolved_at"));
    }

    private static Long nLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).longValue();
    }
}
