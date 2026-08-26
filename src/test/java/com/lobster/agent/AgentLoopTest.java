package com.lobster.agent;

import com.lobster.event.*;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.LlmProvider;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.PermissionReply;
import com.lobster.tool.ToolRegistry;
import com.lobster.tool.builtin.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    @Test
    void loopRunsToolAndFinishes(@TempDir Path tmp) throws Exception {
        try (AgentDb db = AgentDb.open(tmp, "dev")) {
            var store = new MessageStore(db);
            var bus = new EventBus(db);
            var llm = com.lobster.llm.MockLlmProvider.ofTurns(List.of(
                    List.of(
                            new LlmEvent.ToolCall("call_1", "bash", "{\"command\":\"echo lobster\"}"),
                            new LlmEvent.TextDelta("完成"),
                            new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(100, 20))),
                    List.of(
                            new LlmEvent.TextDelta("完成"),
                            new LlmEvent.Finish("stop", new LlmEvent.Usage(100, 20)))));
            var engine = new PermissionEngine(List.of(
                    new PermissionRule("bash", "*", PermissionRule.Action.ALLOW)), null);
            var loop = new AgentLoop(store, bus,
                    ToolRegistry.of(new BashTool()), engine, llm, "dev-01", "m1");

            var s = store.createSession("main", "main", tmp.toString());
            store.appendUser(s.id(), List.of(new Part.Text("跑一下 echo", false, false)));
            loop.run(s.id());

            var msgs = store.loadActive(s.id());
            // user + assistant(text+tool) + user(tool result) + assistant(text)
            assertEquals(4, msgs.size());
            var tool = msgs.get(1).parts().stream()
                    .filter(p -> p instanceof Part.Tool)
                    .map(p -> (Part.Tool) p)
                    .findFirst().orElseThrow();
            assertInstanceOf(Part.ToolState.Completed.class, tool.state());
            assertTrue(((Part.ToolState.Completed) tool.state()).output().contains("lobster"));
            assertTrue(msgs.get(3).parts().stream()
                    .anyMatch(p -> p instanceof Part.Text t && t.text().contains("完成")));

            // durable 事件已落库
            var replayed = bus.replay(s.id(), 0);
            assertTrue(replayed.stream().anyMatch(e -> e.type().equals(Events.TOOL_SUCCESS)));
            assertTrue(replayed.stream().anyMatch(e -> e.type().equals(Events.STEP_ENDED)));
        }
    }

    @Test
    void deniedToolProducesErrorPart(@TempDir Path tmp) throws Exception {
        try (AgentDb db = AgentDb.open(tmp, "dev")) {
            var store = new MessageStore(db);
            var bus = new EventBus(db);
            var llm = com.lobster.llm.MockLlmProvider.ofTurns(List.of(
                    List.of(new LlmEvent.ToolCall("call_1", "bash", "{\"command\":\"rm -rf x\"}"),
                            new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(10, 2))),
                    List.of(new LlmEvent.Finish("stop", new LlmEvent.Usage(10, 2)))));
            var engine = new PermissionEngine(List.of(
                    new PermissionRule("bash", "rm *", PermissionRule.Action.DENY)), null);
            var loop = new AgentLoop(store, bus,
                    ToolRegistry.of(new BashTool()), engine, llm, "dev-01", "m1");

            var s = store.createSession("main", "main", tmp.toString());
            store.appendUser(s.id(), List.of(new Part.Text("删掉", false, false)));
            loop.run(s.id());

            var msgs = store.loadActive(s.id());
            var tool = msgs.get(1).parts().stream()
                    .filter(p -> p instanceof Part.Tool)
                    .map(p -> (Part.Tool) p)
                    .findFirst().orElseThrow();
            assertInstanceOf(Part.ToolState.Error.class, tool.state());
        }
    }

    @Test
    void liveTextDeltaEventsBroadcast(@TempDir Path tmp) throws Exception {
        try (AgentDb db = AgentDb.open(tmp, "dev")) {
            var store = new MessageStore(db);
            var bus = new EventBus(db);
            var deltas = new java.util.concurrent.CopyOnWriteArrayList<LobsterEvent>();
            bus.subscribeAll(e -> { if (Events.TEXT_DELTA.equals(e.type())) deltas.add(e); });

            var llm = new MockLlmProvider(List.of(
                    new LlmEvent.TextDelta("a"),
                    new LlmEvent.TextDelta("b"),
                    new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 2))));
            var engine = new PermissionEngine(List.of(), null);
            var loop = new AgentLoop(store, bus, ToolRegistry.of(), engine, llm, "dev-01", "m1");

            var s = store.createSession("main", "main", tmp.toString());
            store.appendUser(s.id(), List.of(new Part.Text("hi", false, false)));
            loop.run(s.id());

            assertEquals(2, deltas.size());
        }
    }
}
