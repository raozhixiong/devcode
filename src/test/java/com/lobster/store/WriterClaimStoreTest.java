package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** writer claim 围栏：互斥 claim、代际校验、释放、孤儿清理。 */
class WriterClaimStoreTest {

    @TempDir Path tmp;

    @Test
    void mutualExclusionAndRelease() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "claim");
        WriterClaimStore claims = new WriterClaimStore(db);

        var c1 = claims.claim("main", "run_1");
        assertNotNull(c1, "第一个 claim 应成功");

        // 同 sessionKey 第二个 run 被拒
        assertNull(claims.claim("main", "run_2"), "互斥：第二个 claim 应失败");

        // 代际校验
        assertTrue(claims.validate(c1));
        assertFalse(claims.validate(null));
        assertFalse(claims.validate(new WriterClaimStore.Claim("main", "run_1", "fake-gen")));

        // 释放后可再 claim
        claims.release(c1);
        assertFalse(claims.validate(c1), "释放后应失效");
        var c2 = claims.claim("main", "run_2");
        assertNotNull(c2);

        // 孤儿清理（崩溃恢复）：清掉 run_2 的 claim 后可再 claim
        assertEquals(1, claims.clearOrphans());
        var c3 = claims.claim("main", "run_3");
        assertNotNull(c3, "清孤儿后应可重新 claim");
    }

    @Test
    void orphanClearAllowsReclaim() {
        AgentDb db = AgentDb.open(tmp.resolve("agents"), "claim2");
        WriterClaimStore claims = new WriterClaimStore(db);
        var c = claims.claim("main", "run_1");
        assertNotNull(c);
        // 模拟崩溃：直接 clearOrphans（无 release）
        assertEquals(1, claims.clearOrphans());
        var c2 = claims.claim("main", "run_2");
        assertNotNull(c2, "孤儿清理后应可重新 claim");
    }
}
