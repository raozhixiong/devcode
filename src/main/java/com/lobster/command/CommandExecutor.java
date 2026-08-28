package com.lobster.command;

import com.lobster.agent.AgentLoop;
import com.lobster.store.MessageStore;
import com.lobster.store.ShareService;
import com.lobster.util.Ulid;

/** 命令服务端执行器（FR-I2「程序调用」位）：按 slash 执行内置动作。 */
public class CommandExecutor {

    private final MessageStore messageStore;
    private final AgentLoop agentLoop;
    private final ShareService shareService;

    public CommandExecutor(MessageStore messageStore, AgentLoop agentLoop, ShareService shareService) {
        this.messageStore = messageStore;
        this.agentLoop = agentLoop;
        this.shareService = shareService;
    }

    public record Result(boolean ok, String output) {}

    public Result execute(String slash, String sessionId) {
        return switch (slash) {
            case "/clear" -> {
                messageStore.clearSession(sessionId);
                yield new Result(true, "已清空会话 " + sessionId);
            }
            case "/share" -> {
                String token = shareService.create(sessionId);
                yield new Result(true, "分享链接: /share/" + token);
            }
            case "/new" -> {
                var s = messageStore.createSession("cmd-" + Ulid.next("s_"), "conversation",
                        System.getProperty("user.dir"));
                yield new Result(true, "已创建会话: " + s.sessionKey());
            }
            case "/compact" -> {
                agentLoop.compactNow(sessionId);
                yield new Result(true, "已触发上下文压缩");
            }
            default -> new Result(false, "未知命令: " + slash + "（可用 /clear /share /new /compact）");
        };
    }
}
