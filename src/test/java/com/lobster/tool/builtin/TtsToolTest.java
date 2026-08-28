package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.tool.ToolResult;
import com.lobster.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TtsToolTest {

    @Test
    void unconfiguredReturnsHint() throws Exception {
        TtsTool tool = new TtsTool(Path.of("."), k -> "");
        ToolResult r = tool.execute(new ObjectMapper().readTree("{\"text\":\"hi\"}"), ToolContext.dummy());
        assertTrue(r.output().contains("未配置"));
    }

    @Test
    void requiresText() throws Exception {
        TtsTool tool = new TtsTool(Path.of("."), k -> "http://x");
        ToolResult r = tool.execute(new ObjectMapper().readTree("{}"), ToolContext.dummy());
        assertTrue(r.output().contains("text 必填"));
    }
}
