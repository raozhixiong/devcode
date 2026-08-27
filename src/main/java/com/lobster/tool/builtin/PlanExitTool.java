package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.agent.PlanMode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/** plan_exit：Plan 模式交接工具。携带计划摘要退出，等待用户确认切回 Build。 */
public class PlanExitTool implements Tool {

    private final PlanMode planMode;

    public PlanExitTool(PlanMode planMode) {
        this.planMode = planMode;
    }

    @Override public String id() { return "plan_exit"; }

    @Override public String description() {
        return "Exit plan mode and hand off the implementation plan for user confirmation. "
                + "Call this after the plan is written to plans/*.md.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "planFile", Map.of("type", "string", "description", "计划文件路径（plans/*.md）"),
                        "summary", Map.of("type", "string", "description", "计划要点摘要")),
                "required", List.of("summary"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        if (!planMode.isPlan(ctx.sessionId())) {
            return ToolResult.of("PlanExit", "Error: not in plan mode");
        }
        String planFile = args.path("planFile").asText("");
        String summary = args.path("summary").asText();
        planMode.setPlan(ctx.sessionId(), false);
        return ToolResult.of("PlanExit",
                "已退出 Plan 模式。计划交接：\n" + summary
                        + (planFile.isEmpty() ? "" : "\n计划文件: " + planFile)
                        + "\n等待用户确认后切换 Build 模式执行。");
    }
}
