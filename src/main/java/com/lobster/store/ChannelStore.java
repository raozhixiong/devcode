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

/** 频道绑定管理（对齐 FR-B3-2）：channel_binding 表 CRUD。 */
public class ChannelStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public ChannelStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record ChannelBinding(String id, String channel, String accountId,
                                 String agentId, String config, long createdAt) {}

    public ChannelBinding create(String channel, String accountId, String agentId, String config) {
        String id = Ulid.next("chb_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO channel_binding(id, channel, account_id, agent_id, config, created_at)
                VALUES(?,?,?,?,?,?)
                """, id, channel, accountId, agentId, config, now);
        publishChanged(id, "created");
        return new ChannelBinding(id, channel, accountId, agentId, config, now);
    }

    public Optional<ChannelBinding> get(String channel, String accountId) {
        return jdbc.query("""
                SELECT id, channel, account_id, agent_id, config, created_at
                FROM channel_binding WHERE channel = ? AND account_id = ?
                """, ChannelStore::map, channel, accountId).stream().findFirst();
    }

    public Optional<ChannelBinding> getById(String id) {
        return jdbc.query("""
                SELECT id, channel, account_id, agent_id, config, created_at
                FROM channel_binding WHERE id = ?
                """, ChannelStore::map, id).stream().findFirst();
    }

    public List<ChannelBinding> list() {
        return jdbc.query("""
                SELECT id, channel, account_id, agent_id, config, created_at
                FROM channel_binding ORDER BY created_at DESC
                """, ChannelStore::map);
    }

    public List<ChannelBinding> listByChannel(String channel) {
        return jdbc.query("""
                SELECT id, channel, account_id, agent_id, config, created_at
                FROM channel_binding WHERE channel = ? ORDER BY created_at DESC
                """, ChannelStore::map, channel);
    }

    public void update(String id, String config) {
        jdbc.update("UPDATE channel_binding SET config=? WHERE id=?", config, id);
        publishChanged(id, "updated");
    }

    public void remove(String id) {
        jdbc.update("DELETE FROM channel_binding WHERE id=?", id);
        publishChanged(id, "removed");
    }

    private static ChannelBinding map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ChannelBinding(
                rs.getString("id"), rs.getString("channel"),
                rs.getString("account_id"), rs.getString("agent_id"),
                rs.getString("config"), rs.getLong("created_at"));
    }

    private void publishChanged(String bindingId, String action) {
        ObjectNode data = OM.createObjectNode().put("bindingId", bindingId).put("action", action);
        bus.publish(new LobsterEvent(Events.CHANNEL_CHANGED, "", data, false));
    }
}
