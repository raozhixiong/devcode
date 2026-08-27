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

/** 任务台账：CRUD + 状态机 + cancel。 */
class TaskStoreTest {

    @TempDir Path tmp;

    private TaskStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new TaskStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndGet() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "do something", "test-task",
                "agt_main", "run-1", null, "agt_main");
        assertEquals("tsk_", t.id().substring(0, 4));
        assertEquals("queued", t.status());
        assertEquals("subagent", t.runtime());
        assertEquals("do something", t.taskText());
        assertEquals("state_changes", t.notifyPolicy());
        assertEquals(0, t.toolUseCount());

        var found = ts.get(t.id());
        assertTrue(found.isPresent());
        assertEquals(t.id(), found.get().id());
    }

    @Test
    void stateMachineRunningToSucceeded() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "task", "label",
                "agt_main", "run-1", null, "agt_main");
        ts.markRunning(t.id());
        assertEquals("running", ts.get(t.id()).orElseThrow().status());
        assertNotNull(ts.get(t.id()).orElseThrow().startedAt());

        ts.markSucceeded(t.id(), "all done");
        var done = ts.get(t.id()).orElseThrow();
        assertEquals("succeeded", done.status());
        assertEquals("all done", done.terminalSummary());
        assertNotNull(done.endedAt());
    }

    @Test
    void markFailed() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "task", "label",
                "agt_main", "run-1", null, "agt_main");
        ts.markRunning(t.id());
        ts.markFailed(t.id(), "boom");
        var f = ts.get(t.id()).orElseThrow();
        assertEquals("failed", f.status());
        assertEquals("boom", f.error());
    }

    @Test
    void cancelOnlyPending() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "task", "label",
                "agt_main", "run-1", null, "agt_main");
        assertTrue(ts.cancel(t.id()));
        assertEquals("cancelled", ts.get(t.id()).orElseThrow().status());
        // 已取消再取消 -> false
        assertFalse(ts.cancel(t.id()));
    }

    @Test
    void cannotCancelSucceeded() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "task", "label",
                "agt_main", "run-1", null, "agt_main");
        ts.markRunning(t.id());
        ts.markSucceeded(t.id(), "done");
        assertFalse(ts.cancel(t.id()));
        assertEquals("succeeded", ts.get(t.id()).orElseThrow().status());
    }

    @Test
    void listByOwnerAndStatus() {
        var ts = store(tmp);
        var t1 = ts.createSubagent("agent:main:main", "t1", "l1",
                "agt_main", "run-1", null, "agt_main");
        var t2 = ts.createSubagent("agent:main:main", "t2", "l2",
                "agt_main", "run-2", null, "agt_main");
        ts.markRunning(t2.id());
        ts.markSucceeded(t2.id(), "done");

        List<TaskStore.TaskRecord> owned = ts.listByOwner("agent:main:main");
        assertEquals(2, owned.size());

        List<TaskStore.TaskRecord> queued = ts.listByStatus(TaskStore.Status.QUEUED);
        assertEquals(1, queued.size());
        assertEquals(t1.id(), queued.get(0).id());

        List<TaskStore.TaskRecord> succeeded = ts.listByStatus(TaskStore.Status.SUCCEEDED);
        assertEquals(1, succeeded.size());
        assertEquals(t2.id(), succeeded.get(0).id());
    }

    @Test
    void updateProgress() {
        var ts = store(tmp);
        var t = ts.createSubagent("agent:main:main", "task", "label",
                "agt_main", "run-1", null, "agt_main");
        ts.markRunning(t.id());
        ts.updateProgress(t.id(), "step 3 done", 5, "bash");
        var p = ts.get(t.id()).orElseThrow();
        assertEquals("step 3 done", p.progressSummary());
        assertEquals(5, p.toolUseCount());
        assertEquals("bash", p.lastToolName());
    }
}
