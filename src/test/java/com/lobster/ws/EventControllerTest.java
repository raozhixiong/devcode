package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SSE 事件恢复：回放 + after 增量。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-sse"})
class EventControllerTest {

    @Autowired TestRestTemplate http;
    @Autowired com.lobster.event.EventBus bus;

    @Test
    void replayWithAfterFilter() {
        bus.publish(new com.lobster.event.LobsterEvent("evt.a", "ses_sse_1",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("n", 1), true));
        bus.publish(new com.lobster.event.LobsterEvent("evt.b", "ses_sse_1",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("n", 2), true));

        var all = http.getForObject("/api/session/ses_sse_1/events", List.class);
        assertEquals(2, all.size());

        // after=1 只剩第二个
        var rest = http.getForObject("/api/session/ses_sse_1/events?after=1", List.class);
        assertEquals(1, rest.size());
        var frame = (java.util.Map<?, ?>) rest.get(0);
        assertEquals("evt.b", frame.get("event"));
        assertEquals(2, ((Number) frame.get("seq")).longValue());
    }
}
