package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InboxStoreTest {

    @TempDir Path tmp;

    @Test
    void enqueueDrainFifo() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "inbox");
        MessageStore messages = new MessageStore(db);
        InboxStore inbox = new InboxStore(db);

        var s = messages.createSession("inbox-test", "main", tmp.toString());
        assertEquals(0, inbox.pendingCount(s.id()));

        inbox.enqueue(s.id(), "第一条");
        inbox.enqueue(s.id(), "第二条");
        assertEquals(2, inbox.pendingCount(s.id()));

        var drained = inbox.drain(s.id());
        assertEquals(List.of("第一条", "第二条"), drained);
        assertEquals(0, inbox.pendingCount(s.id()));
        assertTrue(inbox.drain(s.id()).isEmpty());
    }
}
