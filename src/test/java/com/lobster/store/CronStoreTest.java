package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cron 调度：job CRUD + 运行历史 + heartbeat tick。 */
class CronStoreTest {

    @TempDir Path tmp;

    private CronStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5000);
        cfg.enforceForeignKeys(true);
        ds.setConfig(cfg);
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        var sharedJdbc = new JdbcTemplate(ds);
        long now = System.currentTimeMillis();
        sharedJdbc.update("INSERT INTO agent(id,name,kind,role,workspace_dir,db_path,permission_rules,subagent_depth,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,?)",
                "agt_main","main","agent","developer","ws","db.db","{}",now,now);
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "crontest")) {
            return new CronStore(sharedJdbc, new EventBus(agentDb));
        }
    }

    @Test
    void createAndGetJob() {
        var cs = store(tmp);
        var job = cs.create("agt_main", "每日报告", "0 0 9 * * *", "生成日报", null);
        assertEquals("cron_", job.id().substring(0, 5));
        assertEquals("每日报告", job.name());
        assertEquals("0 0 9 * * *", job.schedule());
        assertEquals("生成日报", job.prompt());
        assertTrue(job.enabled());
        assertTrue(job.nextFireAt() > System.currentTimeMillis());

        var found = cs.get(job.id());
        assertTrue(found.isPresent());
        assertEquals(job.id(), found.get().id());
    }

    @Test
    void invalidCronExpression() {
        var cs = store(tmp);
        assertThrows(IllegalArgumentException.class, () ->
                cs.create("agt_main", "bad", "not-a-cron", "x", null));
    }

    @Test
    void updateAndRemove() {
        var cs = store(tmp);
        var job = cs.create("agt_main", "test", "0 */5 * * * *", "do thing", null);
        var updated = cs.update(job.id(), "renamed", null, "new prompt", null, false);
        assertEquals("renamed", updated.name());
        assertEquals("new prompt", updated.prompt());
        assertFalse(updated.enabled());
        assertEquals(0, updated.nextFireAt());

        cs.remove(job.id());
        assertTrue(cs.get(job.id()).isEmpty());
    }

    @Test
    void runOnceAndFinish() {
        var cs = store(tmp);
        var job = cs.create("agt_main", "manual", "0 0 0 1 1 *", "happy new year", null);
        var run = cs.runOnce(job.id());
        assertEquals("running", run.status());
        assertNotNull(run.startedAt());

        cs.finishRun(run.id(), "succeeded", "sess-123", null);
        var finished = cs.getRun(run.id()).orElseThrow();
        assertEquals("succeeded", finished.status());
        assertEquals("sess-123", finished.runId());
        assertNull(finished.error());
        assertNotNull(finished.endedAt());
    }

    @Test
    void tickFiresDueJobs() throws Exception {
        var cs = store(tmp);
        // 每秒触发
        var job = cs.create("agt_main", "frequent", "0 * * * * *", "tick", null);
        // 手动把 next_fire_at 改为过去时间，模拟到点
        cs.jdbc().update("UPDATE cron_job SET next_fire_at=? WHERE id=?",
                System.currentTimeMillis() - 1000, job.id());

        List<CronStore.DueJob> due = cs.tick();
        assertEquals(1, due.size());
        assertEquals(job.id(), due.get(0).jobId());
        assertEquals("tick", due.get(0).prompt());

        // run 已创建
        var runs = cs.listRuns(job.id());
        assertEquals(1, runs.size());
        assertEquals("running", runs.get(0).status());

        // next_fire_at 已更新为未来时间
        var updated = cs.get(job.id()).orElseThrow();
        assertTrue(updated.nextFireAt() > System.currentTimeMillis());
    }

    @Test
    void tickSkipsFutureJobs() {
        var cs = store(tmp);
        cs.create("agt_main", "future", "0 0 0 1 1 *", "next year", null);
        List<CronStore.DueJob> due = cs.tick();
        assertTrue(due.isEmpty());
    }

    @Test
    void listRunsByJob() {
        var cs = store(tmp);
        var job = cs.create("agt_main", "multi", "0 0 0 1 1 *", "x", null);
        var r1 = cs.runOnce(job.id());
        cs.finishRun(r1.id(), "succeeded", "s1", null);
        var r2 = cs.runOnce(job.id());
        cs.finishRun(r2.id(), "failed", null, "boom");

        var runs = cs.listRuns(job.id());
        assertEquals(2, runs.size());
    }
}
