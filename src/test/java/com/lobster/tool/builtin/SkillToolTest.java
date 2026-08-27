package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.store.SkillsStore;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolTest {

    @TempDir Path tmp;

    private SkillTool tool() throws Exception {
        var skills = new SkillsStore(tmp, new com.lobster.event.EventBus(
                com.lobster.store.AgentDb.open(tmp.resolve("agents"), "test")));
        skills.install("greet", "# Greet\nSay hello to the user.");
        return new SkillTool(skills);
    }

    @Test
    void loadsSkillContent() throws Exception {
        var r = tool().execute(OM().readTree("{\"name\":\"greet\"}"), ToolContext.dummy());
        assertTrue(r.output().contains("Say hello to the user."));
    }

    @Test
    void listsSkills() throws Exception {
        var r = tool().execute(OM().readTree("{\"name\":\"list\"}"), ToolContext.dummy());
        assertTrue(r.output().contains("greet"));
    }

    @Test
    void unknownSkillErrors() throws Exception {
        var r = tool().execute(OM().readTree("{\"name\":\"nope\"}"), ToolContext.dummy());
        assertTrue(r.output().contains("未找到技能"));
    }

    private static ObjectMapper OM() { return new ObjectMapper(); }
}
