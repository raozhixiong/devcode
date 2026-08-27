package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 五层记忆：episodic 写入 + 搜索 + curated + provenance 闸门。 */
class MemoryStoreTest {

    @TempDir Path tmp;

    @Test
    void writeEpisodicAndSearch() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem1")) {
            var ms = new MemoryStore(db, tmp);
            String id = ms.writeEpisodic("sess-1", "今天修复了登录 bug");
            assertTrue(id.startsWith("mem_"));

            var found = ms.get(id);
            assertTrue(found.isPresent());
            assertTrue(found.get().content().contains("修复了登录 bug"));
            assertEquals("transcript", found.get().originClass());

            var results = ms.search("登录", 10);
            assertEquals(1, results.size());
            assertTrue(results.get(0).content().contains("登录"));
        }
    }

    @Test
    void writeCurated() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem2")) {
            var ms = new MemoryStore(db, tmp);
            ms.writeCurated("MEMORY.md", "用户偏好中文回复");
            ms.writeCurated("MEMORY.md", "项目使用 Java 21");
            var curated = ms.curated();
            assertEquals(2, curated.size());
            assertEquals("owner", curated.get(0).originClass());
        }
    }

    @Test
    void searchMultipleChunks() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem3")) {
            var ms = new MemoryStore(db, tmp);
            ms.writeEpisodic("s1", "处理了用户认证");
            ms.writeEpisodic("s2", "处理了数据库迁移");
            ms.writeEpisodic("s3", "修复了认证 token 过期");

            var results = ms.search("认证", 10);
            assertEquals(2, results.size());
        }
    }

    @Test
    void promoteTranscriptToCurated() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem4")) {
            var ms = new MemoryStore(db, tmp);
            String chunkId = ms.writeEpisodic("s1", "用户喜欢简洁代码");
            assertEquals("transcript", ms.get(chunkId).orElseThrow().originClass());

            boolean promoted = ms.promoteToCurated(chunkId, "MEMORY.md");
            assertTrue(promoted);
            assertEquals("owner", ms.get(chunkId).orElseThrow().originClass());

            // 已是 owner，再次晋升 -> false
            assertFalse(ms.promoteToCurated(chunkId, "MEMORY.md"));
        }
    }

    @Test
    void countAndListTranscript() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem5")) {
            var ms = new MemoryStore(db, tmp);
            ms.writeEpisodic("s1", "任务1");
            ms.writeEpisodic("s2", "任务2");
            ms.writeCurated("MEMORY.md", "curated记忆");
            assertEquals(2, ms.countTranscript());
            List<MemoryStore.Chunk> transcripts = ms.transcriptChunks();
            assertEquals(2, transcripts.size());
        }
    }

    @Test
    void recentEpisodicFiltersByDate() {
        try (AgentDb db = AgentDb.open(tmp.resolve("agents"), "mem6")) {
            var ms = new MemoryStore(db, tmp);
            ms.writeEpisodic("s1", "今天的记忆");
            var recent = ms.recentEpisodic(1, 10);
            assertEquals(1, recent.size());
            assertTrue(recent.get(0).content().contains("今天"));
        }
    }
}
