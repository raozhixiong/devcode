package com.lobster.permission;

import com.lobster.tool.PermissionReply;
import com.lobster.tool.PermissionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 权限引擎：规则求值（findLast 优先）+ ask 挂起/回复。
 * 无匹配默认 ASK。DENY 直接拒绝；ALLOW 直接放行；ASK 通知 UI 并挂起。
 */
public class PermissionEngine {

    public record PendingPermission(String requestId, PermissionRequest request,
                                    CompletableFuture<PermissionReply> future) {}

    private final List<PermissionRule> baseRules;
    private final List<PermissionRule> approvedRules = new ArrayList<>();
    private final Consumer<PendingPermission> notifier;
    private final ConcurrentHashMap<String, PendingPermission> pending = new ConcurrentHashMap<>();

    public PermissionEngine(List<PermissionRule> rules, Consumer<PendingPermission> notifier) {
        this.baseRules = List.copyOf(rules);
        this.notifier = notifier;
    }

    /** 规则求值：baseRules + approvedRules 顺序扫描，取最后一个匹配。 */
    public PermissionRule.Action evaluate(String permission, String target) {
        PermissionRule.Action result = PermissionRule.Action.ASK;
        List<PermissionRule> all = new ArrayList<>(baseRules);
        synchronized (approvedRules) { all.addAll(approvedRules); }
        for (PermissionRule r : all) {
            if (r.matches(permission, target)) result = r.action();
        }
        return result;
    }

    /** 同步 ask：阻塞直到回复（UI 线程外调用）。 */
    public PermissionReply ask(String permission, List<String> patterns) {
        var req = new PermissionRequest(permission, patterns);
        var action = evaluateFirst(permission, patterns);
        return switch (action) {
            case DENY -> new PermissionReply(PermissionReply.Decision.REJECT, "规则拒绝");
            case ALLOW -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null);
            case ASK -> suspend(req);
        };
    }

    /** 带 sessionId 的 ask：事件路由到对应会话。 */
    public PermissionReply ask(String permission, List<String> patterns, String sessionId) {
        var req = new PermissionRequest(permission, patterns, sessionId);
        var action = evaluateFirst(permission, patterns);
        return switch (action) {
            case DENY -> new PermissionReply(PermissionReply.Decision.REJECT, "规则拒绝");
            case ALLOW -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null);
            case ASK -> suspend(req);
        };
    }

    /** 异步 ask：返回 requestId（引擎生成）供 reply 使用。 */
    public String askAsyncId(String permission, List<String> patterns) {
        var req = new PermissionRequest(permission, patterns);
        var action = evaluateFirst(permission, patterns);
        if (action == PermissionRule.Action.DENY) {
            throw new IllegalStateException("DENY 直接拒绝无需挂起");
        }
        if (action == PermissionRule.Action.ALLOW) {
            throw new IllegalStateException("ALLOW 直接放行无需挂起");
        }
        return suspendAsync(req);
    }

    public void reply(String requestId, PermissionReply reply) {
        var p = pending.remove(requestId);
        if (p == null) return;
        if (reply.decision() == PermissionReply.Decision.ALLOW_ALWAYS) {
            recordApproved(new PermissionRule(p.request().permission(),
                    p.request().patterns().isEmpty() ? "*" : p.request().patterns().get(0),
                    PermissionRule.Action.ALLOW));
        }
        p.future().complete(reply);
    }

    public List<PendingPermission> pending() {
        return List.copyOf(pending.values());
    }

    /** 供测试覆盖。 */
    protected void recordApproved(PermissionRule rule) {
        synchronized (approvedRules) { approvedRules.add(rule); }
    }

    private PermissionRule.Action evaluateFirst(String permission, List<String> patterns) {
        PermissionRule.Action action = PermissionRule.Action.ASK;
        for (String target : patterns) {
            action = evaluate(permission, target);
            if (action != PermissionRule.Action.ASK) break;
        }
        return action;
    }

    private PermissionReply suspend(PermissionRequest req) {
        String id = suspendAsync(req);
        try {
            // 挂起等待回复，上限 30 秒防泄漏
            return pending.get(id).future().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return new PermissionReply(PermissionReply.Decision.REJECT, "等待回复超时");
        }
    }

    private String suspendAsync(PermissionRequest req) {
        String id = UUID.randomUUID().toString();
        var p = new PendingPermission(id, req, new CompletableFuture<>());
        pending.put(id, p);
        if (notifier != null) notifier.accept(p);
        return id;
    }
}
