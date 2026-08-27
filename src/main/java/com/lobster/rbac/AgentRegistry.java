package com.lobster.rbac;

import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Agent 注册表（共享库 agent 表）：agent CRUD = 角色实例化。
 * 每 agent 指向独立 db_path（由 AgentDb 打开时迁移）。
 */
public class AgentRegistry {

    private final JdbcTemplate jdbc;
    private final Path stateDir;

    public AgentRegistry(JdbcTemplate sharedJdbc, Path stateDir) {
        this.jdbc = sharedJdbc;
        this.stateDir = stateDir;
    }

    public record AgentRecord(String id, String name, String kind, String role, String emoji,
                              String workspaceDir, String dbPath, String modelProvider, String modelId,
                              String permissionRules, String toolProfile, int subagentDepth,
                              boolean hidden, long createdAt, long updatedAt) {}

    /** 创建 agent（角色实例）：workspace 与 db 路径按 stateDir/agents/<id>/ 布局。 */
    public AgentRecord create(String name, String role, String emoji, String modelProvider, String modelId) {
        Role r = Role.of(role);
        String id = Ulid.next("agt_");
        long now = System.currentTimeMillis();
        String workspace = stateDir.resolve("agents").resolve(id).resolve("workspace").toString();
        String dbPath = stateDir.resolve("agents").resolve(id).resolve("agent").resolve(id + ".db").toString();
        jdbc.update("""
                INSERT INTO agent(id, name, kind, role, emoji, workspace_dir, db_path,
                                  model_provider, model_id, permission_rules, subagent_depth, created_via, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, name, "agent", role, emoji, workspace, dbPath,
                modelProvider, modelId, r.defaultPermissionRules(), 1, "operator", now, now);
        return get(id).orElseThrow();
    }

    public Optional<AgentRecord> get(String id) {
        List<AgentRecord> rows = jdbc.query(
                "SELECT id, name, kind, role, emoji, workspace_dir, db_path, model_provider, model_id, " +
                        "permission_rules, tool_profile, subagent_depth, hidden, created_at, updated_at FROM agent WHERE id=?",
                (rs, i) -> new AgentRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getInt(12), rs.getInt(13) == 1,
                        rs.getLong(14), rs.getLong(15)),
                id);
        return rows.stream().findFirst();
    }

    public List<AgentRecord> list() {
        return jdbc.query(
                "SELECT id, name, kind, role, emoji, workspace_dir, db_path, model_provider, model_id, " +
                        "permission_rules, tool_profile, subagent_depth, hidden, created_at, updated_at FROM agent ORDER BY created_at",
                (rs, i) -> new AgentRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getInt(12), rs.getInt(13) == 1,
                        rs.getLong(14), rs.getLong(15)));
    }

    public void updateModel(String id, String modelProvider, String modelId) {
        jdbc.update("UPDATE agent SET model_provider=?, model_id=?, updated_at=? WHERE id=?",
                modelProvider, modelId, System.currentTimeMillis(), id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM agent WHERE id=?", id);
    }

    /** 角色工具过滤：该 agent 的 role 决定可用工具。 */
    public java.util.function.Predicate<String> toolFilter(String agentId) {
        return get(agentId).map(a -> (java.util.function.Predicate<String>) toolId -> {
            Role r = Role.of(a.role());
            return r.toolAllowed(toolId);
        }).orElse(toolId -> false);
    }
}
