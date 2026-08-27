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

/** 配置中心：CRUD + patch + reloadKind。 */
class ConfigStoreTest {

    @TempDir Path tmp;

    private ConfigStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new ConfigStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void setAndGet() {
        var cs = store(tmp);
        var entry = cs.set("llm.model", "gpt-4", "admin");
        assertEquals("llm.model", entry.path());
        assertEquals("gpt-4", entry.value());
        assertNotNull(entry.revisionHash());

        var found = cs.get("llm.model");
        assertTrue(found.isPresent());
        assertEquals("gpt-4", found.get().value());
        assertEquals(entry.revisionHash(), found.get().revisionHash());
    }

    @Test
    void setUpdatesExisting() {
        var cs = store(tmp);
        cs.set("ui.theme", "dark", "admin");
        var updated = cs.set("ui.theme", "light", "admin2");
        assertEquals("light", updated.value());
        assertEquals("admin2", updated.updatedBy());
        assertEquals(1, cs.list().size());
    }

    @Test
    void getValueReturnsEmpty() {
        var cs = store(tmp);
        assertTrue(cs.getValue("nonexistent").isEmpty());
    }

    @Test
    void patchMergesFields() {
        var cs = store(tmp);
        cs.set("agent.config", "{\"name\":\"test\",\"version\":1}", "admin");

        var entry = cs.patch("agent.config", "{\"version\":2,\"newField\":\"added\"}", "admin");
        var value = entry.value();
        assertTrue(value.contains("\"version\":2"));
        assertTrue(value.contains("\"newField\":\"added\""));
        assertTrue(value.contains("\"name\":\"test\""));
    }

    @Test
    void patchOnNonExistingCreates() {
        var cs = store(tmp);
        var entry = cs.patch("new.config", "{\"key\":\"value\"}", "admin");
        assertTrue(entry.value().contains("\"key\":\"value\""));
    }

    @Test
    void list() {
        var cs = store(tmp);
        cs.set("a.b", "1", "admin");
        cs.set("c.d", "2", "admin");
        cs.set("e.f", "3", "admin");
        var list = cs.list();
        assertEquals(3, list.size());
    }

    @Test
    void reloadKind() {
        var cs = store(tmp);
        assertEquals("restart", cs.reloadKind("llm.model"));
        assertEquals("restart", cs.reloadKind("provider.openai"));
        assertEquals("hot", cs.reloadKind("permission.bash"));
        assertEquals("hot", cs.reloadKind("agent.config"));
        assertEquals("none", cs.reloadKind("ui.theme"));
        assertEquals("none", cs.reloadKind(null));
    }

    @Test
    void applyBatch() {
        var cs = store(tmp);
        cs.apply(List.of(
                new ConfigStore.ConfigEntry("a", "1", "", null, 0),
                new ConfigStore.ConfigEntry("b", "2", "", null, 0),
                new ConfigStore.ConfigEntry("c", "3", "", null, 0)), "admin");
        assertEquals(3, cs.list().size());
        assertEquals("1", cs.getValue("a").orElseThrow());
        assertEquals("2", cs.getValue("b").orElseThrow());
    }
}
