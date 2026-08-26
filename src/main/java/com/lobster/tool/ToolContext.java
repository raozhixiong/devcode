package com.lobster.tool;

import com.lobster.model.Part;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** 工具执行上下文。 */
public record ToolContext(
        String sessionId,
        String messageId,
        String agentId,
        Runnable abortCheck,
        Consumer<Map<String, Object>> metadata,
        Function<PermissionRequest, PermissionReply> ask) {

    /** 测试用空实现。 */
    public static ToolContext dummy() {
        return new ToolContext("ses_test", "msg_test", "agent_test",
                () -> {}, m -> {}, r -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null));
    }
}
