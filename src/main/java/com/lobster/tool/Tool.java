package com.lobster.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 工具 SPI。参数 schema 用简化 JSON 结构描述。 */
public interface Tool {

    String id();

    String description();

    /** JSON Schema 风格：{"type":"object","properties":{...},"required":[...]} */
    Map<String, Object> parameters();

    ToolResult execute(JsonNode args, ToolContext ctx) throws Exception;
}
