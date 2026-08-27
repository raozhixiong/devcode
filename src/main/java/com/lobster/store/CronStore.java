package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Cron 调度（对齐 FR-C3-1/C3-2）：cron_job CRUD + cron_run 运行历史 + heartbeat。
 * 操作共享库 cron_job / cron_run 表。
 */
public class CronStore {

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public CronStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    JdbcTemplate jdbc() { return jdbc; }

    public record CronJob(
            String id, String agentId, String name, String schedule,
            String prompt, String sessionPolicy, boolean enabled,
            Long nextFireAt, long createdAt, long updatedAt) {}

    public record CronRun(
            String id, String jobId, long fireAt, Long startedAt, Long endedAt,
            String status, String runId, String error) {}

    /** 创建 cron job。 */
    public CronJob create(String agentId, String name, String schedule, String prompt,
                          String sessionPolicy) {
        if (!CronExpression.isValidExpression(schedule)) {
            throw new IllegalArgumentException("无效的 cron 表达式: " + schedule);
        }
        String id = Ulid.next("cron_");
        long now = System.currentTimeMillis();
        long nextFire = computeNextFire(schedule, now);
        jdbc.update("""
                INSERT INTO cron_job(id, agent_id, name, schedule, prompt, session_policy,
                                     enabled, next_fire_at, created_at, updated_at)
                VALUES(?,?,?,?,?,?,1,?,?,?)
                """,
                id, agentId, name, schedule, prompt, sessionPolicy,
                nextFire, now, now);
        publishChanged(id, "created");
        return get(id).orElseThrow();
    }

    public Optional<CronJob> get(String id) {
        List<CronJob> rows = jdbc.query(SELECT_JOB + " WHERE id=?", this::mapJob, id);
        return rows.stream().findFirst();
    }

    public List<CronJob> list() {
        return jdbc.query(SELECT_JOB + " ORDER BY created_at", this::mapJob);
    }

    public List<CronJob> listEnabled() {
        return jdbc.query(SELECT_JOB + " WHERE enabled=1 ORDER BY next_fire_at", this::mapJob);
    }

    /** 更新 cron job。 */
    public CronJob update(String id, String name, String schedule, String prompt,
                          String sessionPolicy, Boolean enabled) {
        var existing = get(id).orElseThrow(() -> new IllegalArgumentException("cron job 不存在: " + id));
        String newSchedule = schedule != null ? schedule : existing.schedule();
        if (!CronExpression.isValidExpression(newSchedule)) {
            throw new IllegalArgumentException("无效的 cron 表达式: " + newSchedule);
        }
        long now = System.currentTimeMillis();
        long nextFire = (enabled == null || enabled) ? computeNextFire(newSchedule, now) : 0;
        jdbc.update("""
                UPDATE cron_job SET name=?, schedule=?, prompt=?, session_policy=?,
                                    enabled=?, next_fire_at=?, updated_at=? WHERE id=?
                """,
                name != null ? name : existing.name(),
                newSchedule,
                prompt != null ? prompt : existing.prompt(),
                sessionPolicy != null ? sessionPolicy : existing.sessionPolicy(),
                enabled != null ? (enabled ? 1 : 0) : (existing.enabled() ? 1 : 0),
                nextFire, now, id);
        publishChanged(id, "updated");
        return get(id).orElseThrow();
    }

    public void remove(String id) {
        jdbc.update("DELETE FROM cron_job WHERE id=?", id);
        publishChanged(id, "removed");
    }

    /** 手动触发一次运行。 */
    public CronRun runOnce(String id) {
        var job = get(id).orElseThrow();
        long now = System.currentTimeMillis();
        String runId = Ulid.next("crun_");
        jdbc.update("""
                INSERT INTO cron_run(id, job_id, fire_at, started_at, status) VALUES(?,?,?,?,'running')
                """,
                runId, id, now, now);
        return getRun(runId).orElseThrow();
    }

    /** 标记运行完成。 */
    public void finishRun(String runId, String status, String sessionRunId, String error) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE cron_run SET ended_at=?, status=?, run_id=?, error=? WHERE id=?",
                now, status, sessionRunId, error, runId);
    }

    public Optional<CronRun> getRun(String runId) {
        List<CronRun> rows = jdbc.query(
                "SELECT id, job_id, fire_at, started_at, ended_at, status, run_id, error FROM cron_run WHERE id=?",
                this::mapRun, runId);
        return rows.stream().findFirst();
    }

    public List<CronRun> listRuns(String jobId) {
        return jdbc.query(
                "SELECT id, job_id, fire_at, started_at, ended_at, status, run_id, error "
                        + "FROM cron_run WHERE job_id=? ORDER BY fire_at DESC",
                this::mapRun, jobId);
    }

    /** heartbeat：扫描到点的 job，返回需触发的 (jobId, prompt, agentId) 列表。 */
    public List<DueJob> tick() {
        long now = System.currentTimeMillis();
        List<DueJob> due = new java.util.ArrayList<>();
        for (var job : listEnabled()) {
            if (job.nextFireAt() != null && job.nextFireAt() <= now) {
                String runId = Ulid.next("crun_");
                jdbc.update("INSERT INTO cron_run(id, job_id, fire_at, started_at, status) VALUES(?,?,?,?,'running')",
                        runId, job.id(), now, now);
                long nextFire = computeNextFire(job.schedule(), now);
                jdbc.update("UPDATE cron_job SET next_fire_at=?, updated_at=? WHERE id=?",
                        nextFire, now, job.id());
                due.add(new DueJob(job.id(), runId, job.prompt(), job.agentId(), job.sessionPolicy()));
            }
        }
        return due;
    }

    public record DueJob(String jobId, String runId, String prompt, String agentId,
                         String sessionPolicy) {}

    private long computeNextFire(String schedule, long fromMillis) {
        CronExpression cron = CronExpression.parse(schedule);
        LocalDateTime from = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(fromMillis), ZoneId.systemDefault());
        LocalDateTime next = cron.next(from);
        if (next == null) return 0;
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void publishChanged(String jobId, String kind) {
        ObjectNode data = OM.createObjectNode().put("jobId", jobId).put("kind", kind);
        bus.publish(new LobsterEvent(Events.CRON_CHANGED, "", data, false));
    }

    private static final String SELECT_JOB = """
            SELECT id, agent_id, name, schedule, prompt, session_policy,
                   enabled, next_fire_at, created_at, updated_at
            FROM cron_job
            """;

    private CronJob mapJob(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CronJob(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7) == 1,
                rs.getLong(8), rs.getLong(9), rs.getLong(10));
    }

    private CronRun mapRun(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Long startedAt = rs.getLong(4);
        Long endedAt = rs.getLong(5);
        return new CronRun(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.wasNull() ? null : startedAt,
                rs.wasNull() ? null : endedAt,
                rs.getString(6), rs.getString(7), rs.getString(8));
    }
}
