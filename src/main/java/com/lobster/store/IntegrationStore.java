package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 集成/OAuth 连接框架（FR-I4）：integrations + integration_attempts 状态机。 */
public class IntegrationStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public IntegrationStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Integration(String id, String name, String kind, String status,
                              String configJson, long createdAt, long updatedAt) {}

    public record Attempt(String id, String integrationId, String status, String step,
                           long createdAt, long updatedAt) {}

    public Integration install(String name, String kind) {
        String id = Ulid.next("intg_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO integrations(id, name, kind, status, config_json, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?)", id, name, kind, "connecting", "{}", now, now);
        publish();
        Integration it = get(id);
        if (it == null) throw new IllegalStateException("integration not found: " + id);
        return it;
    }

    public Integration get(String id) {
        return jdbc.query("SELECT id, name, kind, status, config_json, created_at, updated_at "
                        + "FROM integrations WHERE id=?", IntegrationStore::map, id)
                .stream().findFirst().orElse(null);
    }

    public List<Integration> list() {
        return jdbc.query("SELECT id, name, kind, status, config_json, created_at, updated_at "
                        + "FROM integrations ORDER BY name", IntegrationStore::map);
    }

    public void setStatus(String id, String status) {
        jdbc.update("UPDATE integrations SET status=?, updated_at=? WHERE id=?",
                status, System.currentTimeMillis(), id);
        publish();
    }

    public void connectKey(String id, String key) {
        jdbc.update("UPDATE integrations SET status=?, config_json=?, updated_at=? WHERE id=?",
                "connected", "{\"key\":\"set\"}", System.currentTimeMillis(), id);
        publish();
    }

    public Attempt startOAuth(String id) {
        String aid = Ulid.next("att_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO integration_attempts(id, integration_id, status, step, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?)", aid, id, "awaiting", "authorize", now, now);
        return getAttempt(aid);
    }

    public Attempt getAttempt(String id) {
        return jdbc.query("SELECT id, integration_id, status, step, created_at, updated_at "
                        + "FROM integration_attempts WHERE id=?", IntegrationStore::mapAttempt, id)
                .stream().findFirst().orElse(null);
    }

    public void completeAttempt(String id, String configJson) {
        var a = getAttempt(id);
        if (a == null) return;
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE integration_attempts SET status=?, step=?, updated_at=? WHERE id=?",
                "completed", "done", now, id);
        jdbc.update("UPDATE integrations SET status=?, config_json=?, updated_at=? WHERE id=?",
                "connected", configJson == null ? "{}" : configJson, now, a.integrationId());
        publish();
    }

    public void cancelAttempt(String id) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE integration_attempts SET status=?, updated_at=? WHERE id=?",
                "cancelled", now, id);
    }

    public void remove(String id) {
        jdbc.update("DELETE FROM integration_attempts WHERE integration_id=?", id);
        jdbc.update("DELETE FROM integrations WHERE id=?", id);
        publish();
    }

    private static Integration map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Integration(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                rs.getString("status"), rs.getString("config_json"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static Attempt mapAttempt(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Attempt(rs.getString("id"), rs.getString("integration_id"), rs.getString("status"),
                rs.getString("step"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private void publish() {
        ObjectNode data = OM.createObjectNode();
        bus.publish(new LobsterEvent(Events.INTEGRATION_CHANGED, "", data, false));
    }
}
