package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationStoreTest {

    @TempDir Path tmp;

    private IntegrationStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (com.lobster.store.AgentDb agentDb = com.lobster.store.AgentDb.open(dir.resolve("agents"), "test")) {
            return new IntegrationStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void installAndList() {
        var s = store(tmp);
        var it = s.install("github", "oauth");
        assertEquals("connecting", it.status());
        assertEquals(1, s.list().size());
    }

    @Test
    void keyConnectSetsConnected() {
        var s = store(tmp);
        var it = s.install("openai", "key");
        s.connectKey(it.id(), "sk-xxx");
        assertEquals("connected", s.get(it.id()).status());
    }

    @Test
    void oauthAttemptLifecycle() {
        var s = store(tmp);
        var it = s.install("google", "oauth");
        var attempt = s.startOAuth(it.id());
        assertEquals("awaiting", attempt.status());
        s.completeAttempt(attempt.id(), "{\"token\":\"t\"}");
        assertEquals("connected", s.get(it.id()).status());
        assertEquals("completed", s.getAttempt(attempt.id()).status());
    }

    @Test
    void removeCascadesAttempts() {
        var s = store(tmp);
        var it = s.install("x", "key");
        var a = s.startOAuth(it.id());
        s.remove(it.id());
        assertNull(s.get(it.id()));
        assertNull(s.getAttempt(a.id()));
    }
}
