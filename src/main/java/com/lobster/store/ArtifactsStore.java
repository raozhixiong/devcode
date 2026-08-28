package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 产物 Artifacts（FR-I6）：会话/代理生成的可附着的文件、图像、链接。 */
public class ArtifactsStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public ArtifactsStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Artifact(String id, String sessionId, String agentId, String kind,
                           String name, String path, String mime, long createdAt) {}

    public Artifact attach(String sessionId, String agentId, String kind, String name,
                           String path, String mime) {
        String id = Ulid.next("art_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO artifacts(id, session_id, agent_id, kind, name, path, mime, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?)", id, sessionId, agentId, kind, name, path, mime, now);
        publish();
        return get(id);
    }

    public Artifact get(String id) {
        return jdbc.query("SELECT id, session_id, agent_id, kind, name, path, mime, created_at "
                        + "FROM artifacts WHERE id=?", ArtifactsStore::map, id)
                .stream().findFirst().orElse(null);
    }

    public List<Artifact> listBySession(String sessionId) {
        return jdbc.query("SELECT id, session_id, agent_id, kind, name, path, mime, created_at "
                        + "FROM artifacts WHERE session_id=? ORDER BY created_at", ArtifactsStore::map, sessionId);
    }

    public List<Artifact> listByAgent(String agentId) {
        return jdbc.query("SELECT id, session_id, agent_id, kind, name, path, mime, created_at "
                        + "FROM artifacts WHERE agent_id=? ORDER BY created_at", ArtifactsStore::map, agentId);
    }

    public void remove(String id) {
        jdbc.update("DELETE FROM artifacts WHERE id=?", id);
        publish();
    }

    private static Artifact map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Artifact(rs.getString("id"), rs.getString("session_id"), rs.getString("agent_id"),
                rs.getString("kind"), rs.getString("name"), rs.getString("path"),
                rs.getString("mime"), rs.getLong("created_at"));
    }

    private void publish() {
        ObjectNode data = OM.createObjectNode();
        bus.publish(new LobsterEvent(Events.ARTIFACT_CHANGED, "", data, false));
    }
}
