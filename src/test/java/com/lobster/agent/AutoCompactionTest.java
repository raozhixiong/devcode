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

import static org.junit.jupiter.api.Assertions.*;

/** 溢出触发自动压缩：contextLimit 调小 -> 多条历史 -> loop 内压缩生效。 */
class AutoCompactionTest {

    @TempDir Path tmp;

    @Test
    void overflowTriggersCompaction() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "autocompact");
        MessageStore store = new MessageStore(db);
        EventBus bus = new EventBus(db);
        var permissions = new PermissionEngine(List.of(), null);
        var llm = MockLlmProvider.ofTurns(List.of(
                List.of(new LlmEvent.TextDelta("好的。"),
                        new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1)))));

        var loop = new AgentLoop(store, bus, ToolRegistry.of(), permissions, llm, "main", "mock");
        loop.setContextLimitForTest(200); // 极小阈值，历史稍长即触发

        var s = store.createSession("autocompact", "main", tmp.toString());
        // 造 12 条历史（约 12*10 CJK tokens = 120 > 200*0.7=140? 造大文本确保超）
        for (int i = 0; i < 12; i++) {
            store.appendUser(s.id(), List.of(new Part.Text("第" + i + "轮很长的历史内容".repeat(30), false, false)));
            var a = store.appendAssistant(s.id());
            store.addPart(a.id(), new Part.Text("第" + i + "轮回答".repeat(20), false, false));
        }
        loop.run(s.id());

        // 压缩后活动消息应远少于 24（保留尾部 6 + baseline）
        var active = store.loadActive(s.id());
        assertTrue(active.size() < 24, "压缩后活动消息应减少，实际 " + active.size());
        // 含 compaction 摘要 part
        boolean hasCompaction = active.stream().flatMap(m -> m.parts().stream())
                .anyMatch(p -> p instanceof Part.Compaction);
        assertTrue(hasCompaction, "应含 Compaction part");
    }
}
