package com.lobster.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    @Test
    void findLastWins() {
        var e = new PermissionEngine(List.of(
                new PermissionRule("edit", "*", PermissionRule.Action.DENY),
                new PermissionRule("edit", "src/**", PermissionRule.Action.ALLOW)),
                r -> new com.lobster.tool.PermissionReply(
                        com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE, null));
        assertEquals(PermissionRule.Action.ALLOW, e.evaluate("edit", "src/Foo.java"));
        assertEquals(PermissionRule.Action.DENY, e.evaluate("edit", "docs/Foo.md"));
    }

    @Test
    void askSuspendsUntilReply() throws Exception {
        var e = new PermissionEngine(List.of(), r -> new com.lobster.tool.PermissionReply(
                com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE, null));
        var future = CompletableFuture.supplyAsync(() ->
                e.ask("bash", List.of("npm test")));
        Thread.sleep(100);
        assertEquals(1, e.pending().size());
        var req = e.pending().get(0);
        e.reply(req.requestId(), new com.lobster.tool.PermissionReply(
                com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE, null));
        assertEquals(com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE,
                future.get(2, TimeUnit.SECONDS).decision());
    }

    @Test
    void denyRuleShortCircuitsAsk() {
        var e = new PermissionEngine(List.of(
                new PermissionRule("bash", "rm *", PermissionRule.Action.DENY)),
                r -> { throw new AssertionError("不应通知"); });
        var reply = e.ask("bash", List.of("rm -rf /"));
        assertFalse(reply.allowed());
    }

    @Test
    void allowAlwaysRecordsApprovedRule() {
        var approved = new java.util.ArrayList<PermissionRule>();
        var e = new PermissionEngine(List.of(), r -> new com.lobster.tool.PermissionReply(
                com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE, null)) {
            @Override protected void recordApproved(PermissionRule rule) {
                approved.add(rule);
                super.recordApproved(rule);
            }
        };
        e.reply(e.askAsyncId("bash", List.of("git *")), new com.lobster.tool.PermissionReply(
                com.lobster.tool.PermissionReply.Decision.ALLOW_ALWAYS, null));
        assertEquals(1, approved.size());
        // ALWAYS 记录后再次 ask 同 pattern 应直接放行
        assertEquals(com.lobster.tool.PermissionReply.Decision.ALLOW_ONCE,
                e.ask("bash", List.of("git status")).decision());
    }
}
