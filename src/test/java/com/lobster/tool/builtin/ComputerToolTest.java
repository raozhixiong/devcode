package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputerToolTest {

    @Test
    void delegatesToBackend() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("action", "click");
        ComputerBackend stub = (action, params) -> result;
        var tool = new ComputerTool(stub);
        assertEquals("computer", tool.id());

        JsonNode args = new ObjectMapper().readTree("{\"action\":\"click\",\"x\":10,\"y\":20}");
        var res = tool.execute(args, ToolContext.dummy());
        assertTrue(res.output().contains("\"status\":\"ok\""));
        assertTrue(res.output().contains("\"action\":\"click\""));
    }

    @Test
    void unknownActionReported() throws Exception {
        ComputerBackend stub = (action, params) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", "未知动作: " + action);
            return r;
        };
        var tool = new ComputerTool(stub);
        JsonNode args = new ObjectMapper().readTree("{\"action\":\"fly\"}");
        var res = tool.execute(args, ToolContext.dummy());
        assertTrue(res.output().contains("error"));
    }
}
