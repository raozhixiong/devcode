package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Usage 统计：agent/会话/日聚合。 */
class UsageStoreTest {

    @TempDir Path tmp;

    @Test
    void accumulateAndQuery() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "usage1")) {
            var store = new MessageStore(db);
            var usage = new UsageStore(db);

            var s1 = store.createSession("sess-1", "main", tmp.toString());
            db.jdbc().update("UPDATE session SET agent_id='main' WHERE id=?", s1.id());
            usage.accumulate(s1.id(), 100, 50, 0.01);
            usage.accumulate(s1.id(), 200, 100, 0.02);

            var su = usage.sessionUsage(s1.id());
            assertNotNull(su);
            assertEquals(300, su.tokensInput());
            assertEquals(150, su.tokensOutput());
            assertEquals(0.03, su.cost(), 0.001);

            var au = usage.usageForAgent("main");
            assertEquals(300, au.totalInput());
            assertEquals(150, au.totalOutput());
            assertEquals(0.03, au.totalCost(), 0.001);
            assertEquals(1, au.sessionCount());
        }
    }

    @Test
    void multipleSessionsByAgent() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "usage2")) {
            var store = new MessageStore(db);
            var usage = new UsageStore(db);

            var s1 = store.createSession("s1", "main", tmp.toString());
            db.jdbc().update("UPDATE session SET agent_id='main' WHERE id=?", s1.id());
            var s2 = store.createSession("s2", "main", tmp.toString());
            db.jdbc().update("UPDATE session SET agent_id='main' WHERE id=?", s2.id());
            usage.accumulate(s1.id(), 100, 50, 0.01);
            usage.accumulate(s2.id(), 200, 100, 0.02);

            List<UsageStore.AgentUsage> byAgent = usage.usageByAgent();
            assertEquals(1, byAgent.size());
            assertEquals(300, byAgent.get(0).totalInput());
            assertEquals(2, byAgent.get(0).sessionCount());

            List<UsageStore.SessionUsage> sessions = usage.listSessions();
            assertEquals(2, sessions.size());
        }
    }

    @Test
    void dailyUsage() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "usage3")) {
            var store = new MessageStore(db);
            var usage = new UsageStore(db);

            var s = store.createSession("s1", "main", tmp.toString());
            usage.accumulate(s.id(), 100, 50, 0.01);

            List<UsageStore.DailyUsage> daily = usage.dailyUsage(7);
            assertFalse(daily.isEmpty());
            assertEquals(100, daily.get(0).totalInput());
        }
    }
}
