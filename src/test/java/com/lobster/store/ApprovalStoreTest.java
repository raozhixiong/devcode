package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 审批中心：create + get + list + resolve + waitDecision + exec policy。 */
class ApprovalStoreTest {

    @TempDir Path tmp;

    private ApprovalStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new ApprovalStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndGet() {
        var as = store(tmp);
        var approval = as.create("exec", "ses_1", "main", "agent:main", "{\"command\":\"rm -rf\"}");
        assertEquals("apv_", approval.id().substring(0, 4));
        assertEquals("exec", approval.kind());
        assertEquals("pending", approval.status());
        assertEquals("agent:main", approval.requester());

        var found = as.get(approval.id());
        assertTrue(found.isPresent());
        assertEquals(approval.id(), found.get().id());
        assertEquals("pending", found.get().status());
    }

    @Test
    void resolveApprove() {
        var as = store(tmp);
        var approval = as.create("exec", "ses_1", "main", "agent:main", "{}");

        var decision = as.resolve(approval.id(), "admin", true, "approved");
        assertTrue(decision.isPresent());
        assertTrue(decision.get().approved());
        assertEquals("admin", decision.get().resolver());

        var resolved = as.get(approval.id()).orElseThrow();
        assertEquals("approved", resolved.status());
        assertEquals("admin", resolved.resolver());
        assertNotNull(resolved.resolvedAt());
    }

    @Test
    void resolveReject() {
        var as = store(tmp);
        var approval = as.create("exec", "ses_1", "main", "agent:main", "{}");

        var decision = as.resolve(approval.id(), "admin", false, "too dangerous");
        assertTrue(decision.isPresent());
        assertFalse(decision.get().approved());
        assertEquals("too dangerous", decision.get().reason());

        var resolved = as.get(approval.id()).orElseThrow();
        assertEquals("rejected", resolved.status());
    }

    @Test
    void resolveAlreadyProcessed() {
        var as = store(tmp);
        var approval = as.create("exec", null, "main", "admin", "{}");
        as.resolve(approval.id(), "admin", true, null);

        var result = as.resolve(approval.id(), "admin2", false, "changed mind");
        assertTrue(result.isEmpty());
    }

    @Test
    void listWithFilters() {
        var as = store(tmp);
        as.create("exec", "ses_a", "main", "r1", "{}");
        as.create("permission", "ses_b", "main", "r2", "{}");
        as.create("exec", "ses_c", "main", "r3", "{}");
        var a4 = as.create("exec", "ses_d", "main", "r4", "{}");
        as.resolve(a4.id(), "admin", true, null);

        var all = as.list(null, null);
        assertEquals(4, all.size());

        var execOnly = as.list("exec", null);
        assertEquals(3, execOnly.size());
        assertTrue(execOnly.stream().allMatch(a -> "exec".equals(a.kind())));

        var pendingOnly = as.list(null, "pending");
        assertEquals(3, pendingOnly.size());
        assertTrue(pendingOnly.stream().allMatch(a -> "pending".equals(a.status())));

        var approvedOnly = as.list(null, "approved");
        assertEquals(1, approvedOnly.size());
        assertEquals(a4.id(), approvedOnly.get(0).id());
    }

    @Test
    void waitDecisionResolved() throws Exception {
        var as = store(tmp);
        var approval = as.create("exec", "ses_1", "main", "agent:main", "{}");

        // 在另一个线程 resolve
        Thread resolver = new Thread(() -> {
            try { Thread.sleep(100); } catch (Exception e) { /* ignore */ }
            as.resolve(approval.id(), "admin", true, "ok");
        });
        resolver.start();

        var decision = as.waitDecision(approval.id(), 5000);
        assertTrue(decision.approved());
        assertEquals("admin", decision.resolver());
        resolver.join();
    }

    @Test
    void waitDecisionTimeout() throws Exception {
        var as = store(tmp);
        var approval = as.create("exec", "ses_1", "main", "agent:main", "{}");

        var decision = as.waitDecision(approval.id(), 200);
        assertFalse(decision.approved());
        assertEquals("超时未决议", decision.reason());
    }

    @Test
    void history() {
        var as = store(tmp);
        as.create("exec", "s1", "main", "r1", "{}");
        as.create("permission", "s2", "main", "r2", "{}");
        as.create("exec", "s3", "main", "r3", "{}");

        var all = as.history(null, null, 10);
        assertEquals(3, all.size());

        var execOnly = as.history("exec", null, 10);
        assertEquals(2, execOnly.size());
    }

    @Test
    void execPolicyGetSet() {
        var as = store(tmp);
        assertNull(as.getPolicy("gateway"));
        as.setPolicy("gateway", "{\"mode\":\"ask\",\"patterns\":[\"*\"]}", "admin");
        assertEquals("{\"mode\":\"ask\",\"patterns\":[\"*\"]}", as.getPolicy("gateway"));

        // Update existing
        as.setPolicy("gateway", "{\"mode\":\"allow\"}", "admin2");
        assertEquals("{\"mode\":\"allow\"}", as.getPolicy("gateway"));

        // Different scope
        assertNull(as.getPolicy("node:dev1"));
        as.setPolicy("node:dev1", "{\"mode\":\"deny\"}", "admin");
        assertEquals("{\"mode\":\"deny\"}", as.getPolicy("node:dev1"));
    }

    @Test
    void waitDecisionAlreadyResolved() throws Exception {
        var as = store(tmp);
        var approval = as.create("exec", null, "main", "admin", "{}");
        as.resolve(approval.id(), "admin", true, "ok");

        // waitDecision on already-resolved approval (no pending future)
        var decision = as.waitDecision(approval.id(), 100);
        assertTrue(decision.approved());
        assertEquals("admin", decision.resolver());
    }
}
