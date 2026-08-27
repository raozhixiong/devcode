package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/** 插件管理（对齐 FR-H-4）：plugin 表 CRUD。 */
public class PluginStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public PluginStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Plugin(String id, String name, String source, String version,
                        boolean enabled, String status, long installedAt) {}

    public Plugin install(String name, String source, String version) {
        String id = Ulid.next("plg_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO plugin(id, name, source, version, enabled, status, installed_at)
                VALUES(?,?,?,?,1,'installed',?)
                """, id, name, source, version, now);
        publishChanged(id, "installed");
        return new Plugin(id, name, source, version, true, "installed", now);
    }

    public Optional<Plugin> get(String id) {
        return jdbc.query("""
                SELECT id, name, source, version, enabled, status, installed_at
                FROM plugin WHERE id = ?
                """, PluginStore::map, id).stream().findFirst();
    }

    public List<Plugin> list() {
        return jdbc.query("""
                SELECT id, name, source, version, enabled, status, installed_at
                FROM plugin ORDER BY installed_at DESC
                """, PluginStore::map);
    }

    public void setEnabled(String id, boolean enabled) {
        jdbc.update("UPDATE plugin SET enabled=?, status=? WHERE id=?",
                enabled ? 1 : 0, enabled ? "enabled" : "disabled", id);
        publishChanged(id, enabled ? "enabled" : "disabled");
    }

    public void uninstall(String id) {
        jdbc.update("DELETE FROM plugin WHERE id=?", id);
        publishChanged(id, "uninstalled");
    }

    private static Plugin map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Plugin(
                rs.getString("id"), rs.getString("name"),
                rs.getString("source"), rs.getString("version"),
                rs.getInt("enabled") == 1,
                rs.getString("status"), rs.getLong("installed_at"));
    }

    private void publishChanged(String pluginId, String action) {
        ObjectNode data = OM.createObjectNode().put("pluginId", pluginId).put("action", action);
        bus.publish(new LobsterEvent(Events.PLUGINS_CHANGED, "", data, false));
    }
}
