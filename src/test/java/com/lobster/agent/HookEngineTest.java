package com.lobster.agent;

import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.store.HookStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    @TempDir Path tmp;

    private HookStore hookStore(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (com.lobster.store.AgentDb agentDb = com.lobster.store.AgentDb.open(dir.resolve("agents"), "test")) {
            return new HookStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void successWhenRunnerReturnsZero() {
        var hs = hookStore(tmp);
        hs.install("global", null, "tool.before", "command", "echo ok", 5000);
        var engine = new HookEngine(hs, new EventBus(
                com.lobster.store.AgentDb.open(tmp.resolve("agents2"), "test")));
        engine.setCommandRunner((cmd, out) -> { out.append("ran"); return 0; });

        var r = engine.fire("tool.before", "agentX", "sesX", "{}");
        assertFalse(r.blocked());
        assertFalse(r.failed());
    }

    @Test
    void blockedWhenRunnerReturnsTwo() {
        var hs = hookStore(tmp);
        hs.install("global", null, "tool.before", "command", "deny", 5000);
        var engine = new HookEngine(hs, new EventBus(
                com.lobster.store.AgentDb.open(tmp.resolve("agents3"), "test")));
        engine.setCommandRunner((cmd, out) -> 2);

        var r = engine.fire("tool.before", "agentX", "sesX", "{}");
        assertTrue(r.blocked());
    }

    @Test
    void failedButNotBlockingWhenNonZero() {
        var hs = hookStore(tmp);
        hs.install("global", null, "tool.after", "command", "boom", 5000);
        var engine = new HookEngine(hs, new EventBus(
                com.lobster.store.AgentDb.open(tmp.resolve("agents4"), "test")));
        engine.setCommandRunner((cmd, out) -> 1);

        var r = engine.fire("tool.after", "agentX", "sesX", "{}");
        assertFalse(r.blocked());
        assertTrue(r.failed());
    }

    @Test
    void publishesHookFiredEvent() {
        var hs = hookStore(tmp);
        hs.install("global", null, "agent.run.started", "command", "echo", 5000);
        var bus = new EventBus(com.lobster.store.AgentDb.open(tmp.resolve("agents5"), "test"));
        var engine = new HookEngine(hs, bus);
        engine.setCommandRunner((cmd, out) -> 0);
        AtomicReference<String> fired = new AtomicReference<>();
        bus.subscribeAll(e -> {
            if (Events.HOOK_FIRED.equals(e.type())) fired.set(e.type());
        });
        engine.fire("agent.run.started", "agentX", "sesX", "{}");
        assertEquals(Events.HOOK_FIRED, fired.get());
    }
}
