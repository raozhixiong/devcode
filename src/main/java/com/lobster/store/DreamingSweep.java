package com.lobster.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dreaming 后台整合（对齐 FR-D-4）：
 * 读取 episodic transcript -> 蒸馏候选 -> provenance 闸门晋升 -> 写 DREAMS.md 报告。
 * 简化版：直接晋升所有 transcript 为 curated（标记 reviewed）。
 */
public class DreamingSweep {

    private static final Logger log = LoggerFactory.getLogger(DreamingSweep.class);

    private final MemoryStore memory;

    public DreamingSweep(MemoryStore memory) {
        this.memory = memory;
    }

    public record SweepResult(int reviewed, int promoted, String report) {}

    /** 执行一次 Dreaming 扫描。 */
    public SweepResult sweep() {
        List<MemoryStore.Chunk> transcripts = memory.transcriptChunks();
        int promoted = 0;
        for (var chunk : transcripts) {
            if (memory.promoteToCurated(chunk.id(), "MEMORY.md")) {
                promoted++;
            }
        }
        String report = generateReport(transcripts.size(), promoted);
        log.info("Dreaming 完成: 审查 {} 块, 晋升 {} 块", transcripts.size(), promoted);
        return new SweepResult(transcripts.size(), promoted, report);
    }

    private String generateReport(int reviewed, int promoted) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dreaming Report\n\n");
        sb.append("- 审查记忆块: ").append(reviewed).append("\n");
        sb.append("- 晋升为 curated: ").append(promoted).append("\n");
        sb.append("- 时间: ").append(System.currentTimeMillis()).append("\n");
        return sb.toString();
    }
}
