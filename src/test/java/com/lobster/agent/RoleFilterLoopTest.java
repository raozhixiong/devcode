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
import com.lobster.tool.builtin.BashTool;
import com.lobster.tool.builtin.ReadTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AgentLoop 角色工具过滤：reviewer 角色调 bash 被拒，read 放行。 */
class RoleFilterLoopTest {

    static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void reviewerCannotUseBash() throws Exception {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "rolefilter");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("*", "*", PermissionRule.Action.ALLOW)), null);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.ToolCall("c1", "bash",
                                OM.writeValueAsString(Map.of("command", "echo hi"))),
                        new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))),
                List.of(new LlmEvent.TextDelta("受限。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));

        var loop = new AgentLoop(store, bus,
                ToolRegistry.of(new BashTool(), new ReadTool()),
                permissions, llm, "main", "mock");
        // reviewer 角色：无 bash
        loop.setToolFilter(t -> com.lobster.rbac.Role.REVIEWER.toolAllowed(t));

        var s = store.createSession("role-test", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("跑个命令", false, false)));
        loop.run(s.id());

        var bashPart = (Part.Tool) store.loadActive(s.id()).stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof Part.Tool t && "bash".equals(t.tool()))
                .findFirst().orElseThrow();
        assertTrue(bashPart.state() instanceof Part.ToolState.Error e
                && e.error().contains("角色无权限"), "bash 应被角色过滤拒绝: " + bashPart.state());
    }
}
