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

/** 设备管理（对齐 FR-G-2）：device + device_pairing_request 表。 */
public class DeviceStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public DeviceStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record Device(String id, String label, String role, String publicKey,
                         String platform, String access, Long approvedAt,
                         Long lastSeenAt, long createdAt) {}

    public record PairingRequest(String id, String deviceId, String status,
                                 String scopes, long createdAt, Long resolvedAt) {}

    public PairingRequest createPairing(String label, String publicKey,
                                        String platform, String scopes) {
        String id = Ulid.next("pair_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO device_pairing_request(id, device_id, status, scopes, created_at)
                VALUES(?,?,'pending',?,?)
                """, id, null, scopes, now);
        ObjectNode data = OM.createObjectNode()
                .put("pairingId", id)
                .put("label", label == null ? "" : label)
                .put("publicKey", publicKey == null ? "" : publicKey);
        bus.publish(new LobsterEvent(Events.DEVICE_PAIR_REQUESTED, "", data, false));
        return new PairingRequest(id, null, "pending", scopes, now, null);
    }

    public Optional<PairingRequest> findPairing(String pairingId) {
        return jdbc.query("""
                SELECT id, device_id, status, scopes, created_at, resolved_at
                FROM device_pairing_request WHERE id = ?
                """, DeviceStore::mapPairing, pairingId).stream().findFirst();
    }

    public List<PairingRequest> listPendingPairings() {
        return jdbc.query("""
                SELECT id, device_id, status, scopes, created_at, resolved_at
                FROM device_pairing_request WHERE status = 'pending' ORDER BY created_at
                """, DeviceStore::mapPairing);
    }

    public List<PairingRequest> listAllPairings() {
        return jdbc.query("""
                SELECT id, device_id, status, scopes, created_at, resolved_at
                FROM device_pairing_request ORDER BY created_at DESC
                """, DeviceStore::mapPairing);
    }

    public Optional<Device> resolvePairing(String pairingId, boolean approved,
                                            String label, String publicKey,
                                            String platform, String role) {
        var pairing = findPairing(pairingId).orElse(null);
        if (pairing == null || !"pending".equals(pairing.status())) return Optional.empty();
        long now = System.currentTimeMillis();
        Device device = null;
        if (approved) {
            String deviceId = Ulid.next("dev_");
            jdbc.update("""
                    INSERT INTO device(id, label, role, public_key, platform, access, approved_at, created_at)
                    VALUES(?,?,?,?,?,'approved',?,?)
                    """, deviceId, label, role, publicKey, platform, now, now);
            device = findById(deviceId).orElseThrow();
        }
        jdbc.update("UPDATE device_pairing_request SET status=?, resolved_at=? WHERE id=?",
                approved ? "approved" : "rejected", now, pairingId);
        ObjectNode data = OM.createObjectNode()
                .put("pairingId", pairingId)
                .put("status", approved ? "approved" : "rejected");
        if (device != null) data.put("deviceId", device.id());
        bus.publish(new LobsterEvent(Events.DEVICE_PAIR_RESOLVED, "", data, false));
        return Optional.ofNullable(device);
    }

    public Optional<Device> findById(String id) {
        return jdbc.query("""
                SELECT id, label, role, public_key, platform, access, approved_at, last_seen_at, created_at
                FROM device WHERE id = ?
                """, DeviceStore::mapDevice, id).stream().findFirst();
    }

    public List<Device> list() {
        return jdbc.query("""
                SELECT id, label, role, public_key, platform, access, approved_at, last_seen_at, created_at
                FROM device ORDER BY created_at DESC
                """, DeviceStore::mapDevice);
    }

    public void revoke(String deviceId) {
        jdbc.update("DELETE FROM device WHERE id=?", deviceId);
        ObjectNode data = OM.createObjectNode().put("deviceId", deviceId);
        bus.publish(new LobsterEvent(Events.DEVICE_CHANGED, "", data, false));
    }

    public void updateLastSeen(String deviceId) {
        jdbc.update("UPDATE device SET last_seen_at=? WHERE id=?",
                System.currentTimeMillis(), deviceId);
    }

    public void rename(String deviceId, String label) {
        jdbc.update("UPDATE device SET label=? WHERE id=?", label, deviceId);
        ObjectNode data = OM.createObjectNode().put("deviceId", deviceId);
        bus.publish(new LobsterEvent(Events.DEVICE_CHANGED, "", data, false));
    }

    private static Device mapDevice(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Device(
                rs.getString("id"), rs.getString("label"),
                rs.getString("role"), rs.getString("public_key"),
                rs.getString("platform"), rs.getString("access"),
                nLong(rs, "approved_at"),
                nLong(rs, "last_seen_at"),
                rs.getLong("created_at"));
    }

    private static PairingRequest mapPairing(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PairingRequest(
                rs.getString("id"), rs.getString("device_id"),
                rs.getString("status"), rs.getString("scopes"),
                rs.getLong("created_at"),
                nLong(rs, "resolved_at"));
    }

    private static Long nLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).longValue();
    }
}
