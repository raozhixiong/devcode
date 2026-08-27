package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 插件管理：install + list + setEnabled + uninstall。 */
class PluginStoreTest {

    @TempDir Path tmp;

    private PluginStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new PluginStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void installAndGet() {
        var ps = store(tmp);
        var plugin = ps.install("my-tool", "github.com/x/y", "1.0.0");
        assertEquals("plg_", plugin.id().substring(0, 4));
        assertEquals("my-tool", plugin.name());
        assertEquals("1.0.0", plugin.version());
        assertTrue(plugin.enabled());
        assertEquals("installed", plugin.status());

        var found = ps.get(plugin.id());
        assertTrue(found.isPresent());
        assertEquals(plugin.id(), found.get().id());
    }

    @Test
    void list() {
        var ps = store(tmp);
        ps.install("a", "src-a", null);
        ps.install("b", "src-b", "2.0");
        ps.install("c", "src-c", null);

        var list = ps.list();
        assertEquals(3, list.size());
    }

    @Test
    void setEnabled() {
        var ps = store(tmp);
        var plugin = ps.install("test", "src", null);
        assertTrue(plugin.enabled());

        ps.setEnabled(plugin.id(), false);
        var found = ps.get(plugin.id()).orElseThrow();
        assertFalse(found.enabled());
        assertEquals("disabled", found.status());

        ps.setEnabled(plugin.id(), true);
        var found2 = ps.get(plugin.id()).orElseThrow();
        assertTrue(found2.enabled());
        assertEquals("enabled", found2.status());
    }

    @Test
    void uninstall() {
        var ps = store(tmp);
        var plugin = ps.install("temp", "src", null);
        ps.uninstall(plugin.id());
        assertTrue(ps.get(plugin.id()).isEmpty());
        assertEquals(0, ps.list().size());
    }

    @Test
    void installWithoutVersion() {
        var ps = store(tmp);
        var plugin = ps.install("no-version", "local", null);
        assertNull(plugin.version());
    }
}
