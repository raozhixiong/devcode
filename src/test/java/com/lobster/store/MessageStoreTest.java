package com.lobster.store;

import com.lobster.model.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MessageStoreTest {
    @Test
    void crudLifecycle(@TempDir Path tmp) {
        try (AgentDb db = AgentDb.open(tmp, "dev-01")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("main", "main", "D:/work");
            var user = store.appendUser(s.id(), List.of(new Part.Text("hi", false, false)));
            var asst = store.appendAssistant(s.id());
            store.addPart(asst.id(), new Part.Tool("bash", "call_1",
                    new Part.ToolState.Pending("{}")));
            store.updateToolState(asst.id(), "call_1",
                    new Part.ToolState.Completed("npm test", "ok", null));

            var msgs = store.loadActive(s.id());
            assertEquals(2, msgs.size());
            assertEquals("user", msgs.get(0).role());
            var tool = (Part.Tool) msgs.get(1).parts().get(0);
            assertInstanceOf(Part.ToolState.Completed.class, tool.state());
            assertEquals("npm test", ((Part.ToolState.Completed) tool.state()).title());

            Optional<com.lobster.model.Message> last = store.lastMessage(s.id());
            assertTrue(last.isPresent());
            assertEquals("assistant", last.get().role());
        }
    }
}
