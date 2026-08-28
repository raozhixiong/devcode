package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.store.ReferenceLoader;
import com.lobster.store.ReferenceStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 参考库读取工具（FR-I3 深度）：按名称加载参考库内容。 */
public class ReferenceTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();
    private final ReferenceStore store;
    private final ReferenceLoader loader;
    private final Path cacheDir;

    public ReferenceTool(ReferenceStore store, ReferenceLoader loader, Path cacheDir) {
        this.store = store;
        this.loader = loader;
        this.cacheDir = cacheDir;
    }

    @Override public String id() { return "reference"; }

    @Override public String description() {
        return "读取已挂载参考库（local/url/git）的实际内容，用于回答时引用。参数 name 为参考库名称。";
    }

    @Override public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "参考库名称"));
        p.put("properties", props);
        p.put("required", java.util.List.of("name"));
        return p;
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
        String name = args.path("name").asText();
        var ref = store.getByName(name);
        if (ref == null) return ToolResult.of("reference", "ERROR: 未找到参考库 " + name);
        String content = loader.load(ref, cacheDir);
        return ToolResult.of("reference:" + name, content);
    }
}
