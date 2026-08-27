package com.lobster.agent;

import com.lobster.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssemblerTest {

    @TempDir Path tmp;

    @Test
    void injectsAgentsMdFromAncestor() throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "# 规则\n使用中文回复");
        Path nested = tmp.resolve("a/b/c");
        Files.createDirectories(nested);

        var pa = new PromptAssembler("main", "test-model");
        String prompt = pa.assemble(List.of(), nested);

        assertTrue(prompt.contains("<project-instructions>"));
        assertTrue(prompt.contains("使用中文回复"));
        assertTrue(prompt.contains("Model: test-model"));
    }

    @Test
    void noAgentsMdStillWorks() throws Exception {
        var pa = new PromptAssembler("main", "test-model");
        String prompt = pa.assemble(List.of(), tmp);
        assertFalse(prompt.contains("<project-instructions>"));
        assertTrue(prompt.contains("<env>"));
    }

    @Test
    void toolListIncluded() {
        var pa = new PromptAssembler("main", "test-model");
        var tools = List.of(new LlmProvider.ToolSpec("read", "读文件", java.util.Map.of()));
        String prompt = pa.assemble(tools, tmp);
        assertTrue(prompt.contains("read"));
    }
}
