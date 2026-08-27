package com.lobster.store;

import com.lobster.model.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 上下文压缩：compact 后 loadActive 只返回纪元内消息，含 compaction 摘要。 */
class CompactionTest {

    @TempDir Path tmp;

    @Test
    void compactMarksOldAndStartsNewEpoch() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "compact");
        MessageStore store = new MessageStore(db);
        var s = store.createSession("compact-test", "main", tmp.toString());

        var u1 = store.appendUser(s.id(), List.of(new Part.Text("旧问题1", false, false)));
        var a1 = store.appendAssistant(s.id());
        store.addPart(a1.id(), new Part.Text("旧回答1", false, false));
        var u2 = store.appendUser(s.id(), List.of(new Part.Text("新问题", false, false)));
        var a2 = store.appendAssistant(s.id());
        store.addPart(a2.id(), new Part.Text("新回答", false, false));
        assertEquals(4, store.loadActive(s.id()).size());

        // 压缩：保留 u2 起（u1/a1 被压缩）；loadActive = baseline + u2 + a2 = 3
        var baseline = store.compact(s.id(), u2.id(), "此前讨论了旧问题1并已解答。");
        var active = store.loadActive(s.id());
        assertEquals(3, active.size());
        // 顺序 u2 < a2 < baseline（ULID 单调），baseline 是纪元起点消息（逻辑上代表压缩摘要）
        assertEquals(baseline.id(), active.get(2).id());
        var cp = (Part.Compaction) active.get(2).parts().get(0);
        assertTrue(cp.auto());
        assertEquals("此前讨论了旧问题1并已解答。", cp.summary());

        // 纪元内后续消息正常可见（u2 在 baseline 之前，物理序）
        assertEquals("新问题", ((Part.Text) active.get(0).parts().get(0)).text());
    }

    @Test
    void tokenEstimatorCjkWeighted() {
        var msg = new com.lobster.model.Message("m", "s", "user",
                List.of(new Part.Text("abcd", false, false)), 0);
        assertEquals(1, com.lobster.agent.TokenEstimator.estimate("abcd")); // 4 ascii = 1 token
        assertTrue(com.lobster.agent.TokenEstimator.estimate("中文四个字") >= 5); // CJK 逐字计
        assertTrue(com.lobster.agent.TokenEstimator.estimate(List.of(msg)) >= 1);
    }
}
