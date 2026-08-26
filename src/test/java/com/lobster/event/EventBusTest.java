package com.lobster.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.AgentDb;
import com.lobster.util.Ulid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {
    @Test
    void durablePersistsAndReplays(@TempDir Path tmp) {
        try (var db = com.lobster.store.AgentDb.open(tmp, "a")) {
            EventBus bus = new EventBus(db);
            AtomicReference<LobsterEvent> seen = new AtomicReference<>();
            bus.subscribeAll(seen::set);

            ObjectNode data = JsonNodeFactory.instance.objectNode().put("text", "hello");
            bus.publish(new LobsterEvent(Events.TEXT_ENDED, "ses_1", data, true));

            assertNotNull(seen.get());
            List<LobsterEvent> replayed = bus.replay("ses_1", 0);
            assertEquals(1, replayed.size());
            assertEquals("hello", replayed.get(0).data().get("text").asText());
            assertEquals(0, bus.replay("ses_1", 1).size());
        }
    }

    @Test
    void liveDoesNotPersist(@TempDir Path tmp) {
        try (var db = com.lobster.store.AgentDb.open(tmp, "b")) {
            EventBus bus = new EventBus(db);
            bus.publish(new LobsterEvent(Events.TEXT_DELTA, "ses_2",
                    JsonNodeFactory.instance.objectNode().put("delta", "x"), false));
            assertEquals(0, bus.replay("ses_2", 0).size());
        }
    }

    @Test
    void aggregateSubscriptionRoutes(@TempDir Path tmp) {
        try (var db = com.lobster.store.AgentDb.open(tmp, "c")) {
            EventBus bus = new EventBus(db);
            var got = new java.util.concurrent.atomic.AtomicInteger(0);
            Runnable unsub = bus.subscribe("ses_3", e -> got.incrementAndGet());
            bus.publish(new LobsterEvent(Events.SESSION_STATUS, "ses_3",
                    JsonNodeFactory.instance.objectNode(), false));
            bus.publish(new LobsterEvent(Events.SESSION_STATUS, "ses_other",
                    JsonNodeFactory.instance.objectNode(), false));
            assertEquals(1, got.get());
            unsub.run();
            bus.publish(new LobsterEvent(Events.SESSION_STATUS, "ses_3",
                    JsonNodeFactory.instance.objectNode(), false));
            assertEquals(1, got.get());
        }
    }
}
