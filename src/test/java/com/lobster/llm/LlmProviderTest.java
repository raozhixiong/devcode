package com.lobster.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderTest {
    @Test
    void mockStreamsScript() {
        var mock = new MockLlmProvider(List.of(
                new LlmEvent.TextDelta("你好"),
                new LlmEvent.Finish("stop", new LlmEvent.Usage(10, 5))));
        var events = mock.stream(new LlmProvider.LlmRequest("m", "sys",
                List.of(), List.of(), 0.7)).toList();
        assertEquals(2, events.size());
        assertEquals("你好", ((LlmEvent.TextDelta) events.get(0)).text());
        assertEquals("stop", ((LlmEvent.Finish) events.get(1)).reason());
        assertEquals(10, ((LlmEvent.Finish) events.get(1)).usage().inputTokens());
    }

    @Test
    void mockToolCallScript() {
        var mock = new MockLlmProvider(List.of(
                new LlmEvent.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"),
                new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))));
        var events = mock.stream(new LlmProvider.LlmRequest("m", "sys",
                List.of(), List.of(), 0.7)).toList();
        var call = (LlmEvent.ToolCall) events.get(0);
        assertEquals("bash", call.name());
        assertEquals("call_1", call.callId());
        assertTrue(call.argumentsJson().contains("echo hi"));
    }
}
