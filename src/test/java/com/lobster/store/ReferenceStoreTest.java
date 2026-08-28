package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceStoreTest {

    @TempDir Path tmp;

    private ReferenceStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (com.lobster.store.AgentDb agentDb = com.lobster.store.AgentDb.open(dir.resolve("agents"), "test")) {
            return new ReferenceStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void installListEnableRemove() {
        var s = store(tmp);
        var r = s.install("stdlib", "url", "https://example.com", "标准库参考");
        assertEquals("url", r.kind());
        assertEquals(1, s.list().size());
        assertEquals(1, s.enabled().size());

        s.setEnabled(r.id(), false);
        assertEquals(0, s.enabled().size());

        s.remove(r.id());
        assertEquals(0, s.list().size());
    }
}
