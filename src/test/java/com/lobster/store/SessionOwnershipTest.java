package com.lobster.store;

import com.lobster.model.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 三层归属：Creator/Owner/Participants。 */
class SessionOwnershipTest {

    @TempDir Path tmp;

    @Test
    void creatorOwnerParticipants() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "owner")) {
            MessageStore store = new MessageStore(db);
            SessionOwnership own = new SessionOwnership(db);

            var s = store.createSession("own-test", "main", tmp.toString());
            own.setCreator(s.id(), "user-alice");

            assertEquals("user-alice", own.creator(s.id()));
            assertEquals("user-alice", own.owner(s.id()));
            assertEquals(1, own.listParticipants(s.id()).size());

            // 指派新 owner
            own.assignOwner(s.id(), "user-bob");
            assertEquals("user-bob", own.owner(s.id()));
            assertEquals(2, own.listParticipants(s.id()).size());

            // alice 再参与：upsert 去重
            own.addParticipant(s.id(), "user-alice");
            assertEquals(2, own.listParticipants(s.id()).size());

            // 按 owner 查会话
            List<String> bobSessions = own.listByOwner("user-bob");
            assertEquals(1, bobSessions.size());
            assertEquals(s.id(), bobSessions.get(0));
            assertTrue(own.listByOwner("user-alice").isEmpty());
        }
    }

    @Test
    void participantLimit() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "limit")) {
            MessageStore store = new MessageStore(db);
            SessionOwnership own = new SessionOwnership(db);
            var s = store.createSession("limit-test", "main", tmp.toString());
            own.setCreator(s.id(), "u0");

            // 超过 32 条：最旧被淘汰
            for (int i = 1; i <= 40; i++) {
                own.addParticipant(s.id(), "u" + i);
            }
            var ps = own.listParticipants(s.id());
            assertTrue(ps.size() <= 32, "参与者应 ≤32，实际 " + ps.size());
            // 最旧的 u1 应被淘汰（u2~u40 + u0 = 32？取决于淘汰顺序）
            assertFalse(ps.stream().anyMatch(p -> p.actorId().equals("u1")),
                    "最旧参与者应被淘汰");
        }
    }
}
