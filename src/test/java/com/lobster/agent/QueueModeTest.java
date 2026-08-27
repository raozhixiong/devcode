package com.lobster.agent;

import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 队列模式分流：steer/followup/collect 入队、interrupt 中止。 */
class QueueModeTest {

    @TempDir Path tmp;

    @Test
    void dispatchByMode() {
        QueueMode q = new QueueMode();
        assertEquals(QueueMode.Mode.STEER, q.mode("s1"));

        AtomicBoolean aborted = new AtomicBoolean(false);
        List<String> queued = new java.util.ArrayList<>();

        // steer（默认）：busy 入队不中止
        var d = q.dispatch("s1", true, t -> queued.add(t), () -> aborted.set(true));
        assertTrue(d.queued());
        assertFalse(d.interrupted());
        assertEquals(1, queued.size());

        // followup：同样入队
        q.setMode("s2", QueueMode.Mode.FOLLOWUP);
        d = q.dispatch("s2", true, t -> queued.add(t), () -> aborted.set(true));
        assertTrue(d.queued());
        assertFalse(d.interrupted());

        // collect：合并窗口提示
        q.setMode("s3", QueueMode.Mode.COLLECT);
        d = q.dispatch("s3", true, t -> queued.add(t), () -> aborted.set(true));
        assertTrue(d.queued());
        assertTrue(d.note().contains("合并"));

        // interrupt：请求中止
        q.setMode("s4", QueueMode.Mode.INTERRUPT);
        d = q.dispatch("s4", true, t -> queued.add(t), () -> aborted.set(true));
        assertTrue(d.queued());
        assertTrue(d.interrupted());
        assertTrue(aborted.get());

        // idle：不排队
        d = q.dispatch("s4", false, t -> queued.add(t), () -> aborted.set(true));
        assertFalse(d.queued());
    }

    @Test
    void interruptAbortsRunLoop() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "queue");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(), null);
        // 脚本只有一轮文本，无法直接演示中断时机；改为运行前请求中止，runLoop 首步即终止
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.TextDelta("不应出现"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        var loop = new AgentLoop(store, bus, ToolRegistry.of(), permissions, llm, "main", "mock");

        var s = store.createSession("interrupt-test", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("开始", false, false)));
        loop.requestAbort(s.id());
        loop.run(s.id());

        var texts = store.loadActive(s.id()).stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Text)
                .map(p -> ((Part.Text) p).text())
                .toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("用户已中断")), "应生成中断占位: " + texts);
        assertFalse(texts.stream().anyMatch(t -> t.contains("不应出现")), "中止后不应执行 LLM 轮");
    }

    @Test
    void modeParsing() {
        assertEquals(QueueMode.Mode.STEER, QueueMode.Mode.of(null));
        assertEquals(QueueMode.Mode.STEER, QueueMode.Mode.of("steer"));
        assertEquals(QueueMode.Mode.FOLLOWUP, QueueMode.Mode.of("FOLLOWUP"));
        assertEquals(QueueMode.Mode.COLLECT, QueueMode.Mode.of("collect"));
        assertEquals(QueueMode.Mode.INTERRUPT, QueueMode.Mode.of("interrupt"));
        assertEquals(QueueMode.Mode.STEER, QueueMode.Mode.of("bogus"));
    }
}
