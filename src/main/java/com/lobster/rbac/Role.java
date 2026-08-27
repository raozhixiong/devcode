package com.lobster.rbac;

import java.util.Map;
import java.util.Set;

/**
 * 8 角色定义（对齐 03 需求 §1.2）。每角色绑定工具 allowlist 与权限默认规则。
 * developer 全量；reviewer 只读+diff；tester 有限 bash；其余流程角色最小工具集。
 */
public enum Role {

    DEVELOPER("developer", "开发者：全量代码能力",
            Set.of("read", "write", "edit", "glob", "grep", "bash", "todo",
                    "question", "ls", "task", "background_spawn", "plan_exit")),
    REVIEWER("reviewer", "评审员：代码评审、diff 查看",
            Set.of("read", "glob", "grep", "ls", "todo", "question")),
    TESTER("tester", "测试员：用例生成、测试执行",
            Set.of("read", "write", "glob", "grep", "bash", "todo", "question", "ls")),
    PM("pm", "项目经理：任务分配、进度跟踪",
            Set.of("read", "ls", "todo", "question")),
    OPS("ops", "运维：沙箱管理、自动化、监控",
            Set.of("read", "write", "bash", "glob", "grep", "ls", "todo", "question")),
    APPROVER("approver", "审批员：权限审批、安全",
            Set.of("read", "ls", "question")),
    KNOWLEDGE("knowledge", "知识管理员：记忆库、技能工坊",
            Set.of("read", "write", "glob", "grep", "ls", "todo", "question")),
    ADMIN("admin", "系统管理员：用户/配置/集成管理",
            Set.of("read", "write", "edit", "glob", "grep", "bash", "todo",
                    "question", "ls", "task", "background_spawn", "plan_exit"));

    private final String id;
    private final String description;
    private final Set<String> allowedTools;

    Role(String id, String description, Set<String> allowedTools) {
        this.id = id;
        this.description = description;
        this.allowedTools = allowedTools;
    }

    public String id() { return id; }
    public String description() { return description; }
    public Set<String> allowedTools() { return allowedTools; }

    /** 工具是否对该角色开放。 */
    public boolean toolAllowed(String toolId) {
        return allowedTools.contains(toolId);
    }

    public static Role of(String id) {
        for (Role r : values()) {
            if (r.id.equals(id)) return r;
        }
        throw new IllegalArgumentException("未知角色: " + id);
    }

    /** 角色默认权限规则（JSON 字符串，存 agent.permission_rules）。 */
    public String defaultPermissionRules() {
        return switch (this) {
            case DEVELOPER, ADMIN -> """
                    [{"permission":"bash","pattern":"*","action":"ask"},
                     {"permission":"write","pattern":"*","action":"ask"},
                     {"permission":"edit","pattern":"*","action":"ask"},
                     {"permission":"read","pattern":"*","action":"allow"}]""";
            case TESTER, OPS -> """
                    [{"permission":"bash","pattern":"*","action":"ask"},
                     {"permission":"write","pattern":"*","action":"ask"},
                     {"permission":"read","pattern":"*","action":"allow"}]""";
            default -> """
                    [{"permission":"read","pattern":"*","action":"allow"}]""";
        };
    }
}
