package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** 计算机/浏览器工具（FR-I6）：截图、导航、点击、输入。 */
public class ComputerTool implements Tool {

    private final ComputerBackend backend;
    private static final ObjectMapper OM = new ObjectMapper();

    public ComputerTool(ComputerBackend backend) {
        this.backend = backend;
    }

    @Override
    public String id() { return "computer"; }

    @Override
    public String description() {
        return "对屏幕/浏览器执行操作：screenshot 截图、navigate 导航、click 点击、type 输入、scroll 滚动。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string",
                "description", "screenshot|navigate|click|type|scroll"));
        props.put("text", Map.of("type", "string", "description", "navigate 的 URL 或 type 的文本"));
        props.put("x", Map.of("type", "integer", "description", "click 的 x 坐标"));
        props.put("y", Map.of("type", "integer", "description", "click 的 y 坐标"));
        p.put("properties", props);
        p.put("required", java.util.List.of("action"));
        return p;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String action = args.path("action").asText();
        Map<String, Object> out = backend.act(action, args);
        return ToolResult.of("computer:" + action, OM.writeValueAsString(out));
    }
}
