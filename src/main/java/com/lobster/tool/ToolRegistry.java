package com.lobster.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 简单工具注册表。 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;

    private ToolRegistry(Map<String, Tool> tools) {
        this.tools = tools;
    }

    public static ToolRegistry of(Tool... tools) {
        return new ToolRegistry(Arrays.stream(tools)
                .collect(Collectors.toUnmodifiableMap(Tool::id, t -> t)));
    }

    public Tool get(String id) {
        return tools.get(id);
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }
}
