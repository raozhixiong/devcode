package com.lobster.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 审计台账：record + list + findBySession + cleanup。 */
class AuditStoreTest {

    @TempDir Path tmp;

    private AuditStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        return new AuditStore(new JdbcTemplate(ds));
    }

    @Test
    void recordAndList() {
        var as = store(tmp);
        as.record("agent:main", "run.start", "ses_1", "main", "started", null);
        as.record("agent:main", "run.end", "ses_1", "main", "completed", "{\"steps\":3}");

        var events = as.list(null, 10, null);
        assertEquals(2, events.size());
        // DESC by ts — run.end should be last (most recent)
        assertEquals("run.end", events.get(0).kind());
        assertEquals("completed", events.get(0).result());
        assertEquals("run.start", events.get(1).kind());
    }

    @Test
    void listByKind() {
        var as = store(tmp);
        as.record("agent:main", "run.start", "ses_1", "main", "started", null);
        as.record("agent:main", "tool.exec", "ses_1", "main", "success", "{\"toolName\":\"bash\"}");
        as.record("agent:main", "run.end", "ses_1", "main", "completed", null);

        var toolEvents = as.list("tool.exec", 10, null);
        assertEquals(1, toolEvents.size());
        assertEquals("tool.exec", toolEvents.get(0).kind());
    }

    @Test
    void listBySession() {
        var as = store(tmp);
        as.record("agent:main", "run.start", "ses_a", "main", "started", null);
        as.record("agent:main", "run.start", "ses_b", "main", "started", null);
        as.record("agent:main", "tool.exec", "ses_a", "main", "success", null);

        var events = as.listBySession("ses_a", 10);
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> "ses_a".equals(e.sessionKey())));
    }

    @Test
    void findById() {
        var as = store(tmp);
        as.record("agent:main", "run.start", "ses_1", "main", "started", null);
        var events = as.list(null, 10, null);
        var id = events.get(0).id();

        var found = as.findById(id);
        assertTrue(found.isPresent());
        assertEquals(id, found.get().id());
        assertEquals("run.start", found.get().kind());
    }

    @Test
    void cleanup() {
        var as = store(tmp);
        as.record("agent:main", "run.start", "ses_1", "main", "started", null);
        assertEquals(1, as.count());

        long oldTs = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000;
        int deleted = as.cleanup(oldTs);
        // Current events are newer than 31 days, so nothing deleted
        assertEquals(0, deleted);
        assertEquals(1, as.count());

        // Cleanup with current timestamp deletes all
        int deleted2 = as.cleanup(System.currentTimeMillis() + 1);
        assertEquals(1, deleted2);
        assertEquals(0, as.count());
    }

    @Test
    void listByActor() {
        var as = store(tmp);
        as.record("user:admin", "auth.bootstrap", null, "main", "success", null);
        as.record("user:dev1", "auth.login", null, "main", "success", null);
        as.record("user:admin", "auth.token.revoke", null, "main", "success", null);

        var events = as.listByActor("user:admin", 10);
        assertEquals(2, events.size());
    }

    @Test
    void beforeTsPagination() throws Exception {
        var as = store(tmp);
        as.record("a", "run.start", "s1", "main", "ok", null);
        Thread.sleep(10);
        as.record("a", "run.end", "s1", "main", "ok", null);
        Thread.sleep(10);
        as.record("a", "tool.exec", "s1", "main", "ok", null);

        var all = as.list(null, 10, null);
        assertEquals(3, all.size());

        // Page 2: skip the most recent
        var page2 = as.list(null, 10, all.get(0).ts());
        assertEquals(2, page2.size());
    }
}
