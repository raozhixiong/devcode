package com.lobster.store;

import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 五层记忆（对齐 FR-D-1~D-3）：
 * - Episodic：会话结束后写 memory_chunk（origin_class=transcript）
 * - Curated：MEMORY.md/USER.md（origin_class=owner，需确定性闸门晋升）
 * - Review：DREAMS.md（Dreaming 报告）
 * - memory_search：LIKE 搜索 memory_chunk
 * - provenance 闸门：origin_class=owner 可直接晋升 curated；transcript 需连续 2 次 dreaming 确认
 */
public class MemoryStore {

    private final JdbcTemplate jdbc;
    private final java.nio.file.Path workspaceDir;

    public MemoryStore(AgentDb db, java.nio.file.Path workspaceDir) {
        this.jdbc = db.jdbc();
        this.workspaceDir = workspaceDir;
    }

    public enum Tier { INSTRUCTIONS, CURATED, EPISODIC, PROSPECTIVE, REVIEW }
    public enum OriginClass { OWNER, TRANSCRIPT, IMPORT }

    public record Chunk(String id, String sourceId, int chunkIndex, String content,
                        String originClass, long createdAt) {}
    public record Source(String id, String path, String tier, long updatedAt) {}

    /** 写入 episodic 记忆块（会话结束调用）。 */
    public String writeEpisodic(String sessionKey, String content) {
        String datePath = "memory/" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".md";
        String sourceId = ensureSource(datePath, Tier.EPISODIC);
        int chunkIndex = nextChunkIndex(sourceId);
        String chunkId = Ulid.next("mem_");
        long now = System.currentTimeMillis();
        String fullContent = "## session: " + sessionKey + " @ " + now + "\n" + content;
        jdbc.update("INSERT INTO memory_chunk(id, source_id, chunk_index, content, origin_class, created_at) VALUES(?,?,?,?,?,?)",
                chunkId, sourceId, chunkIndex, fullContent, OriginClass.TRANSCRIPT.name().toLowerCase(), now);
        return chunkId;
    }

    /** 写入 curated 记忆块（owner 来源，可直接晋升）。 */
    public String writeCurated(String path, String content) {
        String sourceId = ensureSource(path, Tier.CURATED);
        int chunkIndex = nextChunkIndex(sourceId);
        String chunkId = Ulid.next("mem_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO memory_chunk(id, source_id, chunk_index, content, origin_class, created_at) VALUES(?,?,?,?,?,?)",
                chunkId, sourceId, chunkIndex, content, OriginClass.OWNER.name().toLowerCase(), now);
        return chunkId;
    }

    /** 搜索记忆（LIKE 模糊匹配）。 */
    public List<Chunk> search(String query, int limit) {
        return jdbc.query("""
                SELECT c.id, c.source_id, c.chunk_index, c.content, c.origin_class, c.created_at
                FROM memory_chunk c
                WHERE c.content LIKE ?
                ORDER BY c.created_at DESC
                LIMIT ?
                """,
                (rs, i) -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                "%" + query + "%", limit);
    }

    /** 获取单个记忆块。 */
    public Optional<Chunk> get(String chunkId) {
        List<Chunk> rows = jdbc.query(
                "SELECT id, source_id, chunk_index, content, origin_class, created_at FROM memory_chunk WHERE id=?",
                (rs, i) -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                chunkId);
        return rows.stream().findFirst();
    }

    /** 列出近期 episodic 记忆（用于 bare /new 注入）。 */
    public List<Chunk> recentEpisodic(int days, int limit) {
        long since = System.currentTimeMillis() - (long) days * 86400000L;
        return jdbc.query("""
                SELECT c.id, c.source_id, c.chunk_index, c.content, c.origin_class, c.created_at
                FROM memory_chunk c JOIN memory_source s ON c.source_id = s.id
                WHERE s.tier=? AND c.created_at >= ?
                ORDER BY c.created_at DESC
                LIMIT ?
                """,
                (rs, i) -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                Tier.EPISODIC.name().toLowerCase(), since, limit);
    }

    /** 列出 curated 记忆（用于每次会话开始注入）。 */
    public List<Chunk> curated() {
        return jdbc.query("""
                SELECT c.id, c.source_id, c.chunk_index, c.content, c.origin_class, c.created_at
                FROM memory_chunk c JOIN memory_source s ON c.source_id = s.id
                WHERE s.tier=?
                ORDER BY c.created_at
                """,
                (rs, i) -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                Tier.CURATED.name().toLowerCase());
    }

    /** 晋升 transcript 来源的记忆块为 curated（Dreaming 确认后调用）。 */
    public boolean promoteToCurated(String chunkId, String curatedPath) {
        int n = jdbc.update("UPDATE memory_chunk SET origin_class=? WHERE id=? AND origin_class=?",
                OriginClass.OWNER.name().toLowerCase(), chunkId,
                OriginClass.TRANSCRIPT.name().toLowerCase());
        if (n == 0) return false;
        String sourceId = ensureSource(curatedPath, Tier.CURATED);
        jdbc.update("UPDATE memory_chunk SET source_id=? WHERE id=?", sourceId, chunkId);
        return true;
    }

    /** 统计 transcript 来源的记忆块数（Dreaming 用）。 */
    public int countTranscript() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_chunk WHERE origin_class=?",
                Integer.class, OriginClass.TRANSCRIPT.name().toLowerCase());
        return n == null ? 0 : n;
    }

    /** 获取所有 transcript 来源的记忆块（Dreaming 蒸馏用）。 */
    public List<Chunk> transcriptChunks() {
        return jdbc.query("""
                SELECT id, source_id, chunk_index, content, origin_class, created_at
                FROM memory_chunk WHERE origin_class=?
                ORDER BY created_at
                """,
                (rs, i) -> new Chunk(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                OriginClass.TRANSCRIPT.name().toLowerCase());
    }

    private String ensureSource(String path, Tier tier) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT OR IGNORE INTO memory_source(id, path, tier, updated_at) VALUES(?,?,?,?)",
                Ulid.next("msrc_"), path, tier.name().toLowerCase(), now);
        List<String> ids = jdbc.query("SELECT id FROM memory_source WHERE path=?",
                (rs, i) -> rs.getString(1), path);
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE memory_source SET updated_at=? WHERE path=?", now, path);
            return ids.get(0);
        }
        throw new IllegalStateException("无法确保 memory_source: " + path);
    }

    private int nextChunkIndex(String sourceId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(chunk_index), -1) FROM memory_chunk WHERE source_id=?",
                Integer.class, sourceId);
        return (max == null ? 0 : max) + 1;
    }
}
