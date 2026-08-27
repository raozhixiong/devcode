package com.lobster.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.builtin.BackgroundSpawnTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 后台子代理：spawn 立即返回、子代理异步运行、完成后 announce 注入父会话。 */
class BackgroundSpawnToolTest {

    static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void spawnRunsInBackgroundAndAnnounces() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "bgspawn");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("*", "*", PermissionRule.Action.ALLOW)), null);

        // 父轮1：调用 spawn；announce 后父轮2 收尾
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("c1", "background_spawn",
                                OM.writeValueAsString(Map.of(
                                        "taskKey", "t1",
                                        "description", "检索文档",
                                        "prompt", "找出所有文档文件"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("收到后台结果。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));

        var tools = ToolRegistry.of();
        var loop = new AgentLoop(store, bus, tools, permissions, llm, "main", "mock");
        tools.register(new BackgroundSpawnTool(store, bus, loop));

        var s = store.createSession("bg-parent", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("后台查文档", false, false)));

        // 等待 announce 注入父会话（后台线程异步）
        CountDownLatch announced = new CountDownLatch(1);
        Runnable unsub = bus.subscribeAll(e -> {
            if (com.lobster.event.Events.TASK_ANNOUNCED.equals(e.type())
                    && s.id().equals(e.aggregateId())) announced.countDown();
        });

        loop.run(s.id()); // 第一轮：spawn 调用，立即返回
        assertTrue(announced.await(10, TimeUnit.SECONDS), "应在 10 秒内收到 announce 事件");

        // 等父会话第二轮结束（announce 触发的 run）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var msgs = store.loadActive(s.id());
            boolean done = msgs.stream().flatMap(m -> m.parts().stream())
                    .anyMatch(p -> p instanceof Part.Text t && t.text().contains("收到后台结果"));
            if (done) break;
            Thread.sleep(100);
        }
        unsub.run();

        var msgs = store.loadActive(s.id());
        // announce 合成 user 消息
        assertTrue(msgs.stream().flatMap(m -> m.parts().stream())
                        .anyMatch(p -> p instanceof Part.Text t
                                && t.text().contains("[后台任务完成]") && t.synthetic()),
                "应含 announce 合成 user 消息");
        // 父会话最终回复
        assertTrue(msgs.stream().flatMap(m -> m.parts().stream())
                        .anyMatch(p -> p instanceof Part.Text t && t.text().contains("收到后台结果")),
                "父会话应处理 announce 并回复");
    }
}
