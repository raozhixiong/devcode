package com.lobster.store;

import com.lobster.model.Part;
import com.lobster.model.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 会话管理：fork/rewind/归档/标题。 */
class SessionManagementTest {

    @TempDir Path tmp;

    @Test
    void forkCopiesMessagesUpToId() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "fork")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("orig", "main", tmp.toString());
            var u1 = store.appendUser(s.id(), List.of(new Part.Text("问1", false, false)));
            var a1 = store.appendAssistant(s.id());
            store.addPart(a1.id(), new Part.Text("答1", false, false));
            var u2 = store.appendUser(s.id(), List.of(new Part.Text("问2", false, false)));

            Session fork = store.fork(s.id(), u1.id(), "fork-1");
            assertEquals(1, store.loadActive(fork.id()).size(), "fork 应只含 u1（截至 u1）");
            assertEquals("问1", ((Part.Text) store.loadActive(fork.id()).get(0).parts().get(0)).text());
            assertEquals("fork", fork.kind());
        }
    }

    @Test
    void rewindMarksLaterMessages() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "rewind")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("rewind-test", "main", tmp.toString());
            var u1 = store.appendUser(s.id(), List.of(new Part.Text("保留", false, false)));
            var a1 = store.appendAssistant(s.id());
            store.addPart(a1.id(), new Part.Text("保留答", false, false));
            var u2 = store.appendUser(s.id(), List.of(new Part.Text("回退掉", false, false)));

            store.rewind(s.id(), a1.id());
            List<com.lobster.model.Message> active = store.loadActive(s.id());
            assertEquals(2, active.size(), "rewind 后应只剩 u1+a1");
            assertTrue(active.stream().noneMatch(m -> ((Part.Text) m.parts().get(0)).text().contains("回退")));
        }
    }

    @Test
    void archiveAndRestore() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "arch")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("arch-test", "main", tmp.toString());
            assertTrue(store.findByKey("arch-test").isPresent());

            store.archive(s.id());
            assertFalse(store.findByKey("arch-test").isPresent(), "归档后按 key 查不到");

            store.restore(s.id());
            assertTrue(store.findByKey("arch-test").isPresent(), "恢复后可查");
        }
    }

    @Test
    void setTitlePersists() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "title")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("title-test", "main", tmp.toString());
            store.setTitle(s.id(), "重构方案");
            assertEquals("重构方案", store.findById(s.id()).orElseThrow().title());
        }
    }
}
