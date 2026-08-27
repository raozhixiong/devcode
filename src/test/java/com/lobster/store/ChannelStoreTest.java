package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 频道绑定：CRUD。 */
class ChannelStoreTest {

    @TempDir Path tmp;

    private ChannelStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new ChannelStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndGet() {
        var cs = store(tmp);
        var binding = cs.create("webhook", "acct-1", "main", "{\"url\":\"https://example.com\"}");
        assertEquals("chb_", binding.id().substring(0, 4));
        assertEquals("webhook", binding.channel());
        assertEquals("acct-1", binding.accountId());
        assertEquals("main", binding.agentId());

        var found = cs.get("webhook", "acct-1");
        assertTrue(found.isPresent());
        assertEquals(binding.id(), found.get().id());
    }

    @Test
    void list() {
        var cs = store(tmp);
        cs.create("webhook", "a1", "main", null);
        cs.create("wecom", "a2", "main", null);
        cs.create("webhook", "a3", "main", null);

        var all = cs.list();
        assertEquals(3, all.size());

        var webhookOnly = cs.listByChannel("webhook");
        assertEquals(2, webhookOnly.size());
        assertTrue(webhookOnly.stream().allMatch(b -> "webhook".equals(b.channel())));
    }

    @Test
    void update() {
        var cs = store(tmp);
        var binding = cs.create("webhook", "a1", "main", "{}");
        cs.update(binding.id(), "{\"updated\":true}");
        var found = cs.getById(binding.id()).orElseThrow();
        assertEquals("{\"updated\":true}", found.config());
    }

    @Test
    void remove() {
        var cs = store(tmp);
        var binding = cs.create("webhook", "a1", "main", null);
        cs.remove(binding.id());
        assertTrue(cs.get("webhook", "a1").isEmpty());
    }

    @Test
    void duplicateChannelThrows() {
        var cs = store(tmp);
        cs.create("webhook", "a1", "main", null);
        assertThrows(Exception.class, () ->
                cs.create("webhook", "a1", "other", null));
    }

    @Test
    void getById() {
        var cs = store(tmp);
        var binding = cs.create("feishu", "a1", "main", null);
        var found = cs.getById(binding.id());
        assertTrue(found.isPresent());
        assertEquals("feishu", found.get().channel());
    }
}
