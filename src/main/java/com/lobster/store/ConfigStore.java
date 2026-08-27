package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 配置中心（对齐 FR-H-3）：config_state 表 CRUD + revision hash + reloadKind。 */
public class ConfigStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public ConfigStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record ConfigEntry(String path, String value, String revisionHash,
                              String updatedBy, long updatedAt) {}

    public Optional<ConfigEntry> get(String path) {
        return jdbc.query("""
                SELECT path, value, revision_hash, updated_by, updated_at
                FROM config_state WHERE path = ?
                """, ConfigStore::map, path).stream().findFirst();
    }

    public Optional<String> getValue(String path) {
        return get(path).map(ConfigEntry::value);
    }

    public ConfigEntry set(String path, String value, String updatedBy) {
        long now = System.currentTimeMillis();
        String hash = revisionHash(value);
        int updated = jdbc.update("""
                UPDATE config_state SET value=?, revision_hash=?, updated_by=?, updated_at=? WHERE path=?
                """, value, hash, updatedBy, now, path);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO config_state(path, value, revision_hash, updated_by, updated_at)
                    VALUES(?,?,?,?,?)
                    """, path, value, hash, updatedBy, now);
        }
        publishChanged(path, hash);
        return new ConfigEntry(path, value, hash, updatedBy, now);
    }

    public ConfigEntry patch(String path, String patchesJson, String updatedBy) {
        var existing = get(path);
        String currentValue = existing.map(ConfigEntry::value).orElse("{}");
        try {
            var current = OM.readTree(currentValue);
            var patches = OM.readTree(patchesJson);
            if (patches.isObject()) {
                var it = patches.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    ((ObjectNode) current).set(entry.getKey(), entry.getValue());
                }
            }
            return set(path, OM.writeValueAsString(current), updatedBy);
        } catch (Exception e) {
            throw new RuntimeException("patch 失败: " + e.getMessage(), e);
        }
    }

    public void apply(List<ConfigEntry> updates, String updatedBy) {
        for (var u : updates) {
            set(u.path(), u.value(), updatedBy);
        }
    }

    public List<ConfigEntry> list() {
        return jdbc.query("""
                SELECT path, value, revision_hash, updated_by, updated_at
                FROM config_state ORDER BY path
                """, ConfigStore::map);
    }

    public String reloadKind(String path) {
        if (path == null) return "none";
        if (path.startsWith("llm.") || path.startsWith("provider.")) return "restart";
        if (path.startsWith("permission.") || path.startsWith("agent.")) return "hot";
        if (path.startsWith("ui.") || path.startsWith("display.")) return "none";
        return "none";
    }

    private static String revisionHash(String value) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static ConfigEntry map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ConfigEntry(
                rs.getString("path"), rs.getString("value"),
                rs.getString("revision_hash"),
                rs.getString("updated_by"), rs.getLong("updated_at"));
    }

    private void publishChanged(String path, String hash) {
        ObjectNode data = OM.createObjectNode().put("path", path).put("revisionHash", hash);
        bus.publish(new LobsterEvent(Events.CONFIG_CHANGED, "", data, false));
    }
}
