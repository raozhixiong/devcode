package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 钩子存储（FR-I1）：hooks/hook_run 表 CRUD + 按事件/作用域解析。 */
public class HookStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public HookStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Hook(String id, String scope, String scopeId, String event,
                       String kind, String command, boolean enabled, int timeoutMs) {}

    public record HookRun(String id, String hookId, String event, String status,
                          Integer exitCode, String output, long createdAt) {}

    public Hook install(String scope, String scopeId, String event, String kind,
                        String command, int timeoutMs) {
        String id = Ulid.next("hook_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO hooks(id, scope, scope_id, event, kind, command, enabled, timeout_ms, created_at) "
                        + "VALUES(?,?,?,?,?,?,1,?,?)",
                id, scope, scopeId, event, kind, command, timeoutMs, now);
        publishChanged();
        return new Hook(id, scope, scopeId, event, kind, command, true, timeoutMs);
    }

    public List<Hook> list() {
        return jdbc.query("SELECT id, scope, scope_id, event, kind, command, enabled, timeout_ms "
                        + "FROM hooks ORDER BY event, scope", HookStore::map);
    }

    /** 按事件 + 作用域解析应触发的启用钩子（global + 匹配 agent/session）。 */
    public List<Hook> listForEvent(String event, String agentId, String sessionId) {
        return jdbc.query("SELECT id, scope, scope_id, event, kind, command, enabled, timeout_ms "
                        + "FROM hooks WHERE event=? AND enabled=1", HookStore::map, event).stream()
                .filter(h -> matchesScope(h, agentId, sessionId)).toList();
    }

    private boolean matchesScope(Hook h, String agentId, String sessionId) {
        if ("global".equals(h.scope)) return true;
        if ("agent".equals(h.scope)) return h.scopeId() != null && h.scopeId().equals(agentId);
        if ("session".equals(h.scope)) return h.scopeId() != null && h.scopeId().equals(sessionId);
        return false;
    }

    public void setEnabled(String hookId, boolean enabled) {
        jdbc.update("UPDATE hooks SET enabled=? WHERE id=?", enabled ? 1 : 0, hookId);
        publishChanged();
    }

    public void remove(String hookId) {
        jdbc.update("DELETE FROM hooks WHERE id=?", hookId);
        publishChanged();
    }

    public void recordRun(HookRun run) {
        jdbc.update("INSERT INTO hook_run(id, hook_id, event, status, exit_code, output, created_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                run.id(), run.hookId(), run.event(), run.status(), run.exitCode(),
                run.output(), run.createdAt());
    }

    private static Hook map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Hook(rs.getString("id"), rs.getString("scope"), rs.getString("scope_id"),
                rs.getString("event"), rs.getString("kind"), rs.getString("command"),
                rs.getInt("enabled") == 1, rs.getInt("timeout_ms"));
    }

    private void publishChanged() {
        ObjectNode data = OM.createObjectNode();
        bus.publish(new LobsterEvent(Events.HOOKS_CHANGED, "", data, false));
    }
}
