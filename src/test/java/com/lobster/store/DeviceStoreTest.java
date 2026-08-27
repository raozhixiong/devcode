package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 设备管理：配对请求 + 审批 + CRUD。 */
class DeviceStoreTest {

    @TempDir Path tmp;

    private DeviceStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new DeviceStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createPairingAndFind() {
        var ds = store(tmp);
        var pairing = ds.createPairing("my-device", "pubkey123", "windows", "read");
        assertEquals("pair_", pairing.id().substring(0, 5));
        assertEquals("pending", pairing.status());

        var found = ds.findPairing(pairing.id());
        assertTrue(found.isPresent());
        assertEquals("pending", found.get().status());
    }

    @Test
    void resolvePairingApprove() {
        var ds = store(tmp);
        var pairing = ds.createPairing("dev1", "pk1", "linux", "write");

        var device = ds.resolvePairing(pairing.id(), true, "dev1", "pk1", "linux", "developer");
        assertTrue(device.isPresent());
        assertEquals("dev_", device.get().id().substring(0, 4));
        assertEquals("dev1", device.get().label());
        assertEquals("developer", device.get().role());
        assertNotNull(device.get().approvedAt());

        var resolved = ds.findPairing(pairing.id()).orElseThrow();
        assertEquals("approved", resolved.status());
        assertNotNull(resolved.resolvedAt());
    }

    @Test
    void resolvePairingReject() {
        var ds = store(tmp);
        var pairing = ds.createPairing("dev2", "pk2", "mac", "read");
        var device = ds.resolvePairing(pairing.id(), false, null, null, null, null);
        assertTrue(device.isEmpty());

        var resolved = ds.findPairing(pairing.id()).orElseThrow();
        assertEquals("rejected", resolved.status());
    }

    @Test
    void resolveAlreadyProcessedThrows() {
        var ds = store(tmp);
        var pairing = ds.createPairing("dev3", "pk3", "win", "read");
        ds.resolvePairing(pairing.id(), true, "dev3", "pk3", "win", "developer");

        var result = ds.resolvePairing(pairing.id(), true, "dev3", "pk3", "win", "developer");
        assertTrue(result.isEmpty());
    }

    @Test
    void listPendingPairings() {
        var ds = store(tmp);
        ds.createPairing("a", "pk_a", "win", "read");
        ds.createPairing("b", "pk_b", "linux", "write");
        var p3 = ds.createPairing("c", "pk_c", "mac", "read");
        ds.resolvePairing(p3.id(), true, "c", "pk_c", "mac", "developer");

        var pending = ds.listPendingPairings();
        assertEquals(2, pending.size());
    }

    @Test
    void listDevicesAndRevoke() {
        var ds = store(tmp);
        var pairing = ds.createPairing("d1", "pk", "win", "read");
        var device = ds.resolvePairing(pairing.id(), true, "d1", "pk", "win", "developer").orElseThrow();

        var list = ds.list();
        assertEquals(1, list.size());
        assertEquals(device.id(), list.get(0).id());

        ds.revoke(device.id());
        assertTrue(ds.list().isEmpty());
    }

    @Test
    void rename() {
        var ds = store(tmp);
        var pairing = ds.createPairing("old", "pk", "win", "read");
        var device = ds.resolvePairing(pairing.id(), true, "old", "pk", "win", "developer").orElseThrow();
        ds.rename(device.id(), "new-name");
        assertEquals("new-name", ds.findById(device.id()).orElseThrow().label());
    }

    @Test
    void updateLastSeen() {
        var ds = store(tmp);
        var pairing = ds.createPairing("d", "pk", "win", "read");
        var device = ds.resolvePairing(pairing.id(), true, "d", "pk", "win", "developer").orElseThrow();
        assertNull(device.lastSeenAt());
        ds.updateLastSeen(device.id());
        var updated = ds.findById(device.id()).orElseThrow();
        assertNotNull(updated.lastSeenAt());
    }
}
