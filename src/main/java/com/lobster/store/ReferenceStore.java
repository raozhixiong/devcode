package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 参考库 References（FR-I3）：可挂载外部上下文库（local/git/url）。 */
public class ReferenceStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public ReferenceStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Reference(String id, String name, String kind, String uri,
                            String description, boolean enabled) {}

    public Reference install(String name, String kind, String uri, String description) {
        String id = Ulid.next("ref_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ref_entries(id, name, kind, uri, description, enabled, created_at) "
                        + "VALUES(?,?,?,?,?,1,?)", id, name, kind, uri, description, now);
        publish();
        Reference it = get(id);
        if (it == null) throw new IllegalStateException("reference not found: " + id);
        return it;
    }

    public Reference get(String id) {
        return jdbc.query("SELECT id, name, kind, uri, description, enabled FROM ref_entries WHERE id=?",
                        ReferenceStore::map, id).stream().findFirst().orElse(null);
    }

    public Reference getByName(String name) {
        return jdbc.query("SELECT id, name, kind, uri, description, enabled FROM ref_entries WHERE name=?",
                        ReferenceStore::map, name).stream().findFirst().orElse(null);
    }

    public List<Reference> list() {
        return jdbc.query("SELECT id, name, kind, uri, description, enabled FROM ref_entries ORDER BY name",
                ReferenceStore::map);
    }

    public List<Reference> enabled() {
        return list().stream().filter(Reference::enabled).toList();
    }

    public void setEnabled(String id, boolean enabled) {
        jdbc.update("UPDATE ref_entries SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        publish();
    }

    public void remove(String id) {
        jdbc.update("DELETE FROM ref_entries WHERE id=?", id);
        publish();
    }

    private static Reference map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Reference(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                rs.getString("uri"), rs.getString("description"), rs.getInt("enabled") == 1);
    }

    private void publish() {
        ObjectNode data = OM.createObjectNode();
        bus.publish(new LobsterEvent(Events.REFERENCE_CHANGED, "", data, false));
    }
}
