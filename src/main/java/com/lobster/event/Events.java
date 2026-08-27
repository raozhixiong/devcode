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

    private Events() {}
}
