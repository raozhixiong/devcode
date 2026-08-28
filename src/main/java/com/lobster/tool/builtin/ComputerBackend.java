package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** 计算机/浏览器操作后端抽象（FR-I6）。真实实现可对接 Playwright / 系统截图。 */
public interface ComputerBackend {
    Map<String, Object> act(String action, JsonNode params) throws Exception;
}
