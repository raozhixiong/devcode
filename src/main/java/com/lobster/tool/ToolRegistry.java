package com.lobster.tool;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 简单工具注册表（保持注册顺序）。 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public static ToolRegistry of(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    public synchronized void register(Tool tool) {
        tools.put(tool.id(), tool);
    }

    public Tool get(String id) {
        return tools.get(id);
    }

    public List<Tool> all() {
        synchronized (tools) { return List.copyOf(tools.values()); }
    }
}
