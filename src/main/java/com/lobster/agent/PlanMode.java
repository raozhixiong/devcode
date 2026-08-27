package com.lobster.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan/Build 模式管理（每会话独立）。
 * plan 模式 = 只读权限裁剪（写类工具仅允许 todo / plan_exit，可写 plans/*.md 由 write 工具特殊放行）。
 */
public class PlanMode {

    /** plan 模式下完全禁用的工具（写类）。write 例外放行 plans/ 前缀。 */
    public static final java.util.Set<String> PLAN_DENIED_TOOLS = java.util.Set.of(
            "edit", "bash", "question");

    private final Map<String, Boolean> planSessions = new ConcurrentHashMap<>();

    public boolean isPlan(String sessionId) {
        return planSessions.getOrDefault(sessionId, false);
    }

    public void setPlan(String sessionId, boolean plan) {
        if (plan) planSessions.put(sessionId, true);
        else planSessions.remove(sessionId);
    }

    /** plan 模式下工具是否被裁剪禁用。 */
    public boolean isToolDenied(String sessionId, String toolId) {
        return isPlan(sessionId) && PLAN_DENIED_TOOLS.contains(toolId);
    }

    /** plan 模式注入最后一条 user 消息的 reminder 文本。 */
    public String reminder(String sessionId) {
        if (!isPlan(sessionId)) return null;
        return """
                <system-reminder>
                当前处于 Plan 模式：只读调研与规划。
                - 禁止执行写入/执行类操作（edit/bash 已禁用；write 仅允许写 plans/ 目录下的 .md 计划文件）
                - 产出结构化实施计划（背景/目标/步骤/风险/验收标准）
                - 计划完成后调用 plan_exit 工具交接，等待用户确认后切换回 Build 模式执行
                </system-reminder>""";
    }
}
