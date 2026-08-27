package com.lobster.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.ToolRegistry;
import com.lobster.tool.builtin.PlanExitTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plan 模式：工具裁剪 + write 仅 plans/*.md + plan_exit 退出。 */
class PlanModeTest {

    static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path tmp;

    private AgentLoop newLoop(MessageStore store, EventBus bus, MockLlmProvider llm) {
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("*", "*", PermissionRule.Action.ALLOW)), null);
        return new AgentLoop(store, bus, ToolRegistry.of(), permissions, llm, "main", "mock");
    }

    @Test
    void deniedToolInPlanMode() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "plan1");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("c1", "bash",
                                OM.writeValueAsString(Map.of("command", "rm -rf /"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("无法执行，处于 Plan 模式。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        var loop = newLoop(store, bus, llm);

        var s = store.createSession("plan-test", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("清理磁盘", false, false)));
        loop.planMode().setPlan(s.id(), true);
        loop.run(s.id());

        var msgs = store.loadActive(s.id());
        var bashPart = (Part.Tool) msgs.stream().flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Tool t && "bash".equals(t.tool()))
                .findFirst().orElseThrow();
        assertTrue(bashPart.state() instanceof Part.ToolState.Error e
                && e.error().contains("Plan 模式"), "bash 应被裁剪禁用");
    }

    @Test
    void writeOnlyPlansMd() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "plan2");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("c1", "write",
                                OM.writeValueAsString(Map.of(
                                        "file_path", "src/Main.java", "content", "x"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("plan 完成退出。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        var loop = newLoop(store, bus, llm);
        // write 工具注册（裁剪先于执行，但需存在才走到 write 分支判定）
        var tools = com.lobster.tool.ToolRegistry.of(new com.lobster.tool.builtin.WriteTool());
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("*", "*", PermissionRule.Action.ALLOW)), null);
        loop = new AgentLoop(store, bus, tools, permissions, llm, "main", "mock");

        var s = store.createSession("plan-write", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("写计划", false, false)));
        loop.planMode().setPlan(s.id(), true);
        loop.run(s.id());

        var writePart = (Part.Tool) store.loadActive(s.id()).stream().flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Tool t && "write".equals(t.tool()))
                .findFirst().orElseThrow();
        assertTrue(writePart.state() instanceof Part.ToolState.Error e
                        && e.error().contains("plans"),
                "非 plans/*.md 的 write 应被拒绝: " + writePart.state());
    }

    @Test
    void planExitEndsPlan() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "plan3");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("c1", "plan_exit",
                                OM.writeValueAsString(Map.of(
                                        "planFile", "plans/refactor.md",
                                        "summary", "三步重构"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("已交接。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));
        // 单一 loop：plan_exit 工具与 setPlan 用同一 planMode 实例
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("*", "*", PermissionRule.Action.ALLOW)), null);
        var tools = com.lobster.tool.ToolRegistry.of();
        var loop = new AgentLoop(store, bus, tools, permissions, llm, "main", "mock");
        tools.register(new PlanExitTool(loop.planMode()));

        var s = store.createSession("plan-exit", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("出计划", false, false)));
        loop.planMode().setPlan(s.id(), true);
        assertTrue(loop.planMode().isPlan(s.id()));
        loop.run(s.id());

        assertFalse(loop.planMode().isPlan(s.id()), "plan_exit 后应退出 Plan 模式");
        var exitPart = (Part.Tool) store.loadActive(s.id()).stream().flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Tool t && "plan_exit".equals(t.tool()))
                .findFirst().orElseThrow();
        assertTrue(exitPart.state() instanceof Part.ToolState.Completed);
    }

    @Test
    void reminderTextInjected() {
        PlanMode pm = new PlanMode();
        pm.setPlan("ses_x", true);
        assertNotNull(pm.reminder("ses_x"));
        assertNull(pm.reminder("ses_y"));
        assertTrue(pm.isToolDenied("ses_x", "bash"));
        assertFalse(pm.isToolDenied("ses_x", "read"));
        assertFalse(pm.isToolDenied("ses_y", "bash"));
    }
}
