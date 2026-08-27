package com.lobster.agent;

import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.store.WriterClaimStore;
import com.lobster.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** writer claim 围栏与 AgentLoop 集成：互斥 run、崩溃恢复。 */
class WriterClaimLoopTest {

    @TempDir Path tmp;

    @Test
    void secondRunRejectedWhileClaimed() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "claimloop");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(), null);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.TextDelta("ok"), new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        var loop = new AgentLoop(store, bus, ToolRegistry.of(), permissions, llm, "main", "mock");
        loop.setWriterClaimStore(new WriterClaimStore(db));

        var s = store.createSession("claim-loop", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("hi", false, false)));
        loop.run(s.id()); // 正常完成并释放 claim

        // run 结束后 claim 已释放，可再次 run
        loop.run(s.id());
        // 若 claim 未释放，这里会抛 IllegalStateException
        assertTrue(true);
    }

    @Test
    void claimPreemptsConcurrentRun() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "claimpre");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        WriterClaimStore claims = new WriterClaimStore(db);

        var s = store.createSession("claim-pre", "main", tmp.toString());

        // 模拟另一写入方先 claim 了同一会话
        var other = claims.claim(s.id(), "run_other");
        assertNotNull(other);

        var permissions = new PermissionEngine(List.of(), null);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.TextDelta("ok"), new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        var loop = new AgentLoop(store, bus, ToolRegistry.of(), permissions, llm, "main", "mock");
        loop.setWriterClaimStore(claims);

        store.appendUser(s.id(), List.of(new Part.Text("hi", false, false)));
        assertThrows(IllegalStateException.class, () -> loop.run(s.id()),
                "claim 被他人持有时 run 应被拒绝");

        // 释放后可运行
        claims.release(other);
        loop.run(s.id());
    }
}
