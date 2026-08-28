package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactsStoreTest {

    @TempDir Path tmp;

    private ArtifactsStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (com.lobster.store.AgentDb agentDb = com.lobster.store.AgentDb.open(dir.resolve("agents"), "test")) {
            return new ArtifactsStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void attachListRemove() {
        var s = store(tmp);
        var a = s.attach("ses1", "agent1", "image", "shot.png", "/tmp/shot.png", "image/png");
        assertEquals("image", a.kind());
        assertEquals(1, s.listBySession("ses1").size());
        assertEquals(1, s.listByAgent("agent1").size());
        s.remove(a.id());
        assertEquals(0, s.listBySession("ses1").size());
    }
}
