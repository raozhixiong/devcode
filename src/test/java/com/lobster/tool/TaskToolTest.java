package com.lobster.tool;

import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.builtin.TaskTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** task 子代理：父调用 task 工具 -> 子会话跑一轮 -> 父拿到摘要。 */
class TaskToolTest {

    static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void subAgentRunsAndReturnsText() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "task");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("task", "*", PermissionRule.Action.ALLOW)), null);
        // 父轮1：调用 task；父轮2：总结。子会话轮：直接文本回复
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("call-1", "task",
                                OM.writeValueAsString(java.util.Map.of(
                                        "description", "找文件", "prompt", "找到所有 java 文件"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("已完成子任务。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));

        var loop = new AgentLoop(store, bus,
                com.lobster.tool.ToolRegistry.of(new TaskTool(store, null)),
                permissions, llm, "main", "mock");

        // TaskTool 引用 loop（RuntimeConfig 里 post-register）；测试中直接复用同一 loop
        var tools = com.lobster.tool.ToolRegistry.of(new TaskTool(store, loop));
        var parentLoop = new AgentLoop(store, bus, tools, permissions, llm, "main", "mock");
        loop = parentLoop;

        var s = store.createSession("task-parent", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("帮我找文件", false, false)));
        parentLoop.run(s.id());

        // 父会话应有 task tool part + 完成文本
        var msgs = store.loadActive(s.id());
        boolean hasTaskPart = msgs.stream().flatMap(m -> m.parts().stream())
                .anyMatch(p -> p instanceof Part.Tool t && "task".equals(t.tool()));
        assertTrue(hasTaskPart, "父会话应包含 task tool part");

        // task 工具的输出应包含子代理回复
        var taskPart = (Part.Tool) msgs.stream().flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Tool t && "task".equals(t.tool()))
                .findFirst().orElseThrow();
        // 子代理 mock 单脚本模式每轮重复：第一轮文本
        assertTrue(taskPart.state() instanceof Part.ToolState.Completed);
    }
}
