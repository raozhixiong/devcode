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

/**
 * 任务台账（对齐 FR-C1-1/C1-2）：统一 TaskRecord，
 * 状态机 queued -> running -> succeeded/failed/timed_out/cancelled/lost。
 * 操作共享库 task 表。
 */
public class TaskStore {

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public TaskStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public enum Runtime { SUBAGENT, CRON, CLI, ACP }
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, LOST }
    public enum NotifyPolicy { DONE_ONLY, STATE_CHANGES, SILENT }

    public record TaskRecord(
            String id, String runtime, String taskKind, String sourceId,
            String requesterAgentId, String ownerKey, String parentTaskId,
            String agentId, String runId, String label, String taskText,
            String status, String deliveryStatus, String notifyPolicy,
            int toolUseCount, String lastToolName, String error,
            String progressSummary, String terminalSummary, String detail,
            long createdAt, Long startedAt, Long endedAt, Long lastEventAt,
            Long cleanupAfter) {}

    /** 创建任务（status=queued）。 */
    public TaskRecord create(Runtime runtime, String ownerKey, String taskText,
                             String label, String agentId, String runId,
                             String parentTaskId, String requesterAgentId,
                             String taskKind, String sourceId,
                             NotifyPolicy notifyPolicy) {
        String id = Ulid.next("tsk_");
        long now = System.currentTimeMillis();
        String np = (notifyPolicy == null ? NotifyPolicy.STATE_CHANGES : notifyPolicy)
                .name().toLowerCase();
        jdbc.update("""
                INSERT INTO task(id, runtime, task_kind, source_id, requester_agent_id, owner_key,
                                 parent_task_id, agent_id, run_id, label, task_text, status,
                                 notify_policy, tool_use_count, created_at, last_event_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                """,
                id, runtime.name().toLowerCase(),
                taskKind, sourceId, requesterAgentId, ownerKey,
                parentTaskId, agentId, runId, label, taskText,
                Status.QUEUED.name().toLowerCase(),
                np, now, now);
        publishChanged(id, "created");
        return get(id).orElseThrow();
    }

    /** 便捷重载：subagent 后台任务。 */
    public TaskRecord createSubagent(String ownerKey, String taskText, String label,
                                     String agentId, String runId, String parentTaskId,
                                     String requesterAgentId) {
        return create(Runtime.SUBAGENT, ownerKey, taskText, label, agentId, runId,
                parentTaskId, requesterAgentId, "background_spawn", null,
                NotifyPolicy.STATE_CHANGES);
    }

    public Optional<TaskRecord> get(String id) {
        List<TaskRecord> rows = jdbc.query(SELECT_COLS + " WHERE id=?", this::map, id);
        return rows.stream().findFirst();
    }

    public List<TaskRecord> list() {
        return jdbc.query(SELECT_COLS + " ORDER BY created_at DESC", this::map);
    }

    public List<TaskRecord> listByOwner(String ownerKey) {
        return jdbc.query(SELECT_COLS + " WHERE owner_key=? ORDER BY created_at DESC",
                this::map, ownerKey);
    }

    public List<TaskRecord> listByStatus(Status status) {
        return jdbc.query(SELECT_COLS + " WHERE status=? ORDER BY last_event_at DESC",
                this::map, status.name().toLowerCase());
    }

    /** 标记 running（仅 queued 可转）。 */
    public void markRunning(String id) {
        long now = System.currentTimeMillis();
        int n = jdbc.update(
                "UPDATE task SET status='running', started_at=COALESCE(started_at,?), last_event_at=? WHERE id=? AND status='queued'",
                now, now, id);
        if (n > 0) publishChanged(id, "running");
    }

    /** 标记成功。 */
    public void markSucceeded(String id, String terminalSummary) {
        long now = System.currentTimeMillis();
        int n = jdbc.update(
                "UPDATE task SET status='succeeded', terminal_summary=?, ended_at=?, last_event_at=? WHERE id=? AND status IN ('queued','running')",
                terminalSummary, now, now, id);
        if (n > 0) publishChanged(id, "succeeded");
    }

    /** 标记失败。 */
    public void markFailed(String id, String error) {
        long now = System.currentTimeMillis();
        int n = jdbc.update(
                "UPDATE task SET status='failed', error=?, ended_at=?, last_event_at=? WHERE id=? AND status IN ('queued','running')",
                error, now, now, id);
        if (n > 0) publishChanged(id, "failed");
    }

    /** 取消（仅 queued/running 可取消）。 */
    public boolean cancel(String id) {
        long now = System.currentTimeMillis();
        int n = jdbc.update(
                "UPDATE task SET status='cancelled', ended_at=?, last_event_at=? WHERE id=? AND status IN ('queued','running')",
                now, now, id);
        if (n > 0) {
            publishChanged(id, "cancelled");
            return true;
        }
        return false;
    }

    /** 更新进度。 */
    public void updateProgress(String id, String progressSummary, int toolUseCount, String lastToolName) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "UPDATE task SET progress_summary=?, tool_use_count=?, last_tool_name=?, last_event_at=? WHERE id=?",
                progressSummary, toolUseCount, lastToolName, now, id);
        publishChanged(id, "progress");
    }

    private void publishChanged(String taskId, String kind) {
        ObjectNode data = OM.createObjectNode().put("taskId", taskId).put("kind", kind);
        bus.publish(new LobsterEvent(Events.TASKS_CHANGED, "", data, false));
    }

    private static final String SELECT_COLS = """
            SELECT id, runtime, task_kind, source_id, requester_agent_id, owner_key,
                   parent_task_id, agent_id, run_id, label, task_text, status,
                   delivery_status, notify_policy, tool_use_count, last_tool_name,
                   error, progress_summary, terminal_summary, detail,
                   created_at, started_at, ended_at, last_event_at, cleanup_after
            FROM task
            """;

    private TaskRecord map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TaskRecord(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14),
                rs.getInt(15), rs.getString(16), rs.getString(17),
                rs.getString(18), rs.getString(19), rs.getString(20),
                rs.getLong(21), rs.getLong(22), rs.getLong(23), rs.getLong(24), rs.getLong(25));
    }
}
