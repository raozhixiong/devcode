package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HookStoreTest {

    @TempDir Path tmp;

    private HookStore store(Path dir) {
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
    void installAndList() {
        var hs = store(tmp);
        var h = hs.install("global", null, "agent.run.started", "command", "echo hi", 5000);
        assertEquals("agent.run.started", h.event());
        assertEquals(1, hs.list().size());
    }

    @Test
    void listForEventScopes() {
        var hs = store(tmp);
        hs.install("global", null, "tool.before", "command", "echo g", 1000);
        hs.install("agent", "agentA", "tool.before", "command", "echo a", 1000);
        hs.install("session", "sesX", "tool.before", "command", "echo s", 1000);

        var globalOnly = hs.listForEvent("tool.before", "agentB", "sesY");
        assertEquals(1, globalOnly.size());

        var agentPlus = hs.listForEvent("tool.before", "agentA", "sesY");
        assertEquals(2, agentPlus.size());

        var sessionPlus = hs.listForEvent("tool.before", "agentA", "sesX");
        assertEquals(3, sessionPlus.size());
    }

    @Test
    void setEnabledAndRemove() {
        var hs = store(tmp);
        var h = hs.install("global", null, "tool.after", "command", "echo x", 1000);
        hs.setEnabled(h.id(), false);
        assertEquals(0, hs.listForEvent("tool.after", null, null).size());
        hs.setEnabled(h.id(), true);
        assertEquals(1, hs.listForEvent("tool.after", null, null).size());
        hs.remove(h.id());
        assertEquals(0, hs.list().size());
    }
}
