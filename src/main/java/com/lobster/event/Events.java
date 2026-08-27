package com.lobster.event;

/** M1 事件类型常量（对齐 opencode session.next.* 命名）。 */
public final class Events {
    public static final String PROMPT_ADMITTED = "session.next.prompt.admitted";
    public static final String STEP_STARTED = "session.next.step.started";
    public static final String STEP_ENDED = "session.next.step.ended";
    public static final String STEP_FAILED = "session.next.step.failed";
    public static final String TEXT_DELTA = "session.next.text.delta";
    public static final String TEXT_ENDED = "session.next.text.ended";
    public static final String TOOL_CALLED = "session.next.tool.called";
    public static final String TOOL_SUCCESS = "session.next.tool.success";
    public static final String TOOL_FAILED = "session.next.tool.failed";
    public static final String SESSION_STATUS = "session.status";
    public static final String SESSION_IDLE = "session.idle";
    public static final String PERMISSION_ASKED = "permission.asked";
    public static final String PERMISSION_REPLIED = "permission.replied";
    public static final String COMPACTION_STARTED = "session.next.compaction.started";
    public static final String COMPACTION_ENDED = "session.next.compaction.ended";
    public static final String MODE_SWITCHED = "session.mode.switched";
    public static final String AGENT_ANNOUNCED = "agent.announced";
    public static final String TASK_STARTED = "session.task.started";
    public static final String TASK_ANNOUNCED = "session.task.announced";
    public static final String QUEUE_MODE_SET = "session.queue.mode.set";
    public static final String SESSION_STATE_CHANGED = "session.state.changed";
    public static final String TASKS_CHANGED = "tasks.changed";
    public static final String WORKBOARD_CHANGED = "workboard.changed";
    public static final String CRON_CHANGED = "cron.changed";
    public static final String SKILLS_CHANGED = "skills.changed";
    public static final String CONNECT_CHALLENGE = "connect.challenge";
    public static final String AUTH_USER_CHANGED = "auth.user.changed";
    public static final String AUTH_TOKEN_REVOKED = "auth.token.revoked";
    public static final String DEVICE_PAIR_REQUESTED = "device.pair.requested";
    public static final String DEVICE_PAIR_RESOLVED = "device.pair.resolved";
    public static final String DEVICE_CHANGED = "device.changed";
    public static final String AUDIT_CHANGED = "audit.changed";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_RESOLVED = "approval.resolved";
    public static final String CHANNEL_CHANGED = "channel.changed";
    public static final String CONFIG_CHANGED = "config.changed";
    public static final String PLUGINS_CHANGED = "plugins.changed";

    // ---- M6 钩子框架（FR-I1）事件 ----
    public static final String HOOKS_CHANGED = "hooks.changed";
    public static final String HOOK_FIRED = "hook.fired";
    public static final String AGENT_RUN_STARTED = "agent.run.started";
    public static final String AGENT_RUN_ENDED = "agent.run.ended";
    public static final String TOOL_BEFORE = "tool.before";
    public static final String TOOL_AFTER = "tool.after";
    public static final String SKILL_COMPLETED = "skill.completed";
    public static final String MCP_TOOL_COMPLETED = "mcp.tool.completed";
    public static final String MESSAGE_RECEIVED = "message.received";
    public static final String MESSAGE_SENT = "message.sent";
    public static final String COMPACTION_DONE = "compaction.done";
    public static final String CHAT_SYSTEM_TRANSFORM = "chat.system.transform";

    private Events() {}
}
