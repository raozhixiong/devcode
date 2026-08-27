package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.store.SkillsStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** 技能加载工具（FR-A4-1 / FR-F-7）：加载 SKILL.md 指令供模型遵循，或列出可用技能。 */
public class SkillTool implements Tool {

    private final SkillsStore skills;

    public SkillTool(SkillsStore skills) {
        this.skills = skills;
    }

    @Override public String id() { return "skill"; }

    @Override public String description() {
        return "Load and run a workspace skill. Pass name to load its SKILL.md instructions for the current task, "
                + "or name=\"list\" to enumerate available skills.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "技能名，或 'list'"),
                        "args", Map.of("type", "string", "description", "可选参数，注入到技能上下文")),
                "required", List.of("name"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
        String name = args.path("name").asText("");
        if (name.isBlank() || "list".equals(name)) {
            StringBuilder sb = new StringBuilder("可用技能：\n");
            for (var s : skills.list()) {
                sb.append("- ").append(s.name())
                        .append(s.enabled() ? "" : " (disabled)")
                        .append(": ").append(s.description()).append('\n');
            }
            return ToolResult.of("skill list", sb.toString());
        }
        var opt = skills.get(name);
        if (opt.isEmpty()) {
            return ToolResult.of("skill " + name, "Error: 未找到技能: " + name);
        }
        var s = opt.get();
        if (!s.enabled()) {
            return ToolResult.of("skill " + name, "Error: 技能已禁用: " + name);
        }
        String argsStr = args.path("args").asText("");
        String body = s.content();
        if (!argsStr.isBlank()) body += "\n\n<skill-args>" + argsStr + "</skill-args>";
        return ToolResult.of("skill " + name, body);
    }
}
