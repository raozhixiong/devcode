package com.lobster.question;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 问题引擎：挂起等待前端回复（与 PermissionEngine 同构）。
 * Tool 调用 question 时阻塞虚拟线程，前端 question.respond 回复后恢复。
 */
public class QuestionEngine {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QuestionEngine.class);

    public record PendingQuestion(String requestId, String question, List<String> choices,
                                  String sessionId, CompletableFuture<String> future) {}

    private final Consumer<PendingQuestion> notifier;
    private final ConcurrentHashMap<String, PendingQuestion> pending = new ConcurrentHashMap<>();

    public QuestionEngine(Consumer<PendingQuestion> notifier) {
        this.notifier = notifier;
    }

    /**
     * 同步提问：阻塞直到前端回复，上限 30 秒。
     *
     * @return 用户回复文本；超时返回 "（用户未响应）"
     */
    public String ask(String question, List<String> choices, String sessionId) {
        String id = suspendAsync(question, choices, sessionId);
        try {
            return pending.get(id).future().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return "（用户未响应）";
        }
    }

    /** 前端回复：完成 CompletableFuture。 */
    public void reply(String requestId, String answer) {
        var p = pending.remove(requestId);
        if (p == null) {
            log.warn("question.reply 找不到 pending requestId={}", requestId);
            return;
        }
        log.info("question.reply requestId={} answer={}", requestId, answer);
        p.future().complete(answer);
    }

    /** 当前挂起中的问题列表。 */
    public List<PendingQuestion> pending() {
        return List.copyOf(pending.values());
    }

    private String suspendAsync(String question, List<String> choices, String sessionId) {
        String id = UUID.randomUUID().toString();
        var pq = new PendingQuestion(id, question, choices, sessionId, new CompletableFuture<>());
        pending.put(id, pq);
        if (notifier != null) notifier.accept(pq);
        return id;
    }
}
