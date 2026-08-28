package com.lobster.command;

import com.lobster.event.EventBus;
import com.lobster.model.Part;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.store.ShareService;
import com.lobster.util.Ulid;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorTest {

    @TempDir Path tmp;

    private CommandExecutor executor(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb adb = AgentDb.open(dir.resolve("agents"), "test")) {
            var ms = new MessageStore(adb);
            var share = new ShareService(new JdbcTemplate(ds), ms, new EventBus(adb));
            return new CommandExecutor(ms, null, share);
        }
    }

    @Test
    void clearAndShareAndNew() {
        try (AgentDb adb = AgentDb.open(tmp.resolve("agents"), "test")) {
            var ms = new MessageStore(adb);
            var c = executor(tmp);
            var s = ms.createSession("sk_" + Ulid.next("sk_"), "conversation", System.getProperty("user.dir"));
            ms.appendUser(s.id(), java.util.List.of(new Part.Text("hi", false, false)));
            assertEquals(1, ms.loadActive(s.id()).size());

            var r1 = c.execute("/clear", s.id());
            assertTrue(r1.ok());
            assertEquals(0, ms.loadActive(s.id()).size());

            var r2 = c.execute("/share", s.id());
            assertTrue(r2.ok());
            assertTrue(r2.output().contains("/share/"));

            var r3 = c.execute("/new", s.id());
            assertTrue(r3.ok());
            assertTrue(r3.output().contains("已创建会话"));
        }
    }

    @Test
    void unknownCommandErrors() {
        var c = executor(tmp);
        var r = c.execute("/nope", "x");
        assertFalse(r.ok());
    }
}
