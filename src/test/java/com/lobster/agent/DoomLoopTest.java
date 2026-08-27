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
import com.lobster.tool.builtin.ListTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** doom loop：同参数重复调用超过阈值后熔断终止。 */
class DoomLoopTest {

    @TempDir Path tmp;

    @Test
    void repeatedSameArgsToolBreaksLoop() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "doom");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        // 每轮都调用同参数 ls 工具，永不停止
        var llm = new MockLlmProvider(List.of(
                new LlmEvent.ToolCall("call-1", "ls", "{}"),
                new LlmEvent.Finish("tool_calls", new LlmEvent.Usage(1, 1))));

        var loop = new AgentLoop(store, bus, ToolRegistry.of(new ListTool()),
                new PermissionEngine(List.of(
                        new PermissionRule("ls", "*", PermissionRule.Action.ALLOW)), null),
                llm, "main", "mock");

        var s = store.createSession("doom-test", "main", tmp.toString());
        store.appendUser(s.id(), List.of(new Part.Text("列出文件", false, false)));
        loop.run(s.id());

        // 熔断后应有 doom loop 文本 + 循环终止
        var msgs = store.loadActive(s.id());
        boolean hasBreak = msgs.stream().flatMap(m -> m.parts().stream())
                .anyMatch(p -> p instanceof Part.Text t && t.text().contains("doom loop"));
        assertTrue(hasBreak);
        // 未达 MAX_STEPS（50）即提前终止
        long assistantCount = msgs.stream().filter(m -> "assistant".equals(m.role())).count();
        assertTrue(assistantCount < 12, "assistant count=" + assistantCount);
    }
}
