package com.lobster.store;

import com.lobster.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 状态感知：stateVersion 乐观锁 + changesSince 信号。 */
class SessionStateServiceTest {

    static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void versionBumpsAndChangesSince() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "state")) {
            MessageStore store = new MessageStore(db);
            EventBus bus = new EventBus(db);
            SessionStateService svc = new SessionStateService(db, bus);

            var s = store.createSession("state-test", "main", tmp.toString());
            assertEquals(0, svc.getVersion(s.id()));

            ObjectNode p1 = OM.createObjectNode().put("msg", "first change");
            long v1 = svc.bump(s.id(), "message.appended", p1);
            assertEquals(1, v1);
            assertEquals(1, svc.getVersion(s.id()));

            ObjectNode p2 = OM.createObjectNode().put("msg", "second");
            long v2 = svc.bump(s.id(), "session.archived", p2);
            assertEquals(2, v2);

            // since=0 -> 全部两条信号
            List<SessionStateService.Signal> all = svc.changesSince(s.id(), 0);
            assertEquals(2, all.size());
            assertEquals("message.appended", all.get(0).kind());
            assertEquals("session.archived", all.get(1).kind());

            // since=1 -> 只有 v2
            List<SessionStateService.Signal> since1 = svc.changesSince(s.id(), 1);
            assertEquals(1, since1.size());
            assertEquals(2, since1.get(0).stateVersion());

            // since=2 -> 空
            assertTrue(svc.changesSince(s.id(), 2).isEmpty());
        }
    }
}
