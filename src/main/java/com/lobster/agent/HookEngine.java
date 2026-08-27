package com.lobster.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.store.HookStore;
import com.lobster.util.Ulid;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 钩子执行引擎（FR-I1）：加载匹配事件/作用域的钩子并依次执行。
 * - block 语义：命令退出码 2 → 拦截（调用方应中止，如 tool.before）
 * - after 语义：非 0 退出码 → 失败但不阻断主流程
 * 默认执行真实命令；可注入 CommandRunner 用于测试。
 */
public class HookEngine {

    private static final ObjectMapper OM = new ObjectMapper();
    private final HookStore hooks;
    private final EventBus bus;
    private volatile CommandRunner runner = HookEngine::defaultRun;

    public interface CommandRunner {
        int run(String command, StringBuilder output) throws Exception;
    }

    public HookEngine(HookStore hooks, EventBus bus) {
        this.hooks = hooks;
        this.bus = bus;
    }

    public void setCommandRunner(CommandRunner r) { this.runner = r; }

    public record Result(boolean blocked, boolean failed) {}

    public Result fire(String event, String agentId, String sessionId, String payloadJson) {
        List<HookStore.Hook> matched = hooks.listForEvent(event, agentId, sessionId);
        boolean blocked = false;
        boolean failed = false;
        for (var h : matched) {
            StringBuilder out = new StringBuilder();
            int code;
            try {
                code = runner.run(h.command(), out);
            } catch (Exception e) {
                code = -1;
                out.append(e.getMessage());
            }
            String status;
            if (code == 2) { blocked = true; status = "blocked"; }
            else if (code != 0) { failed = true; status = "failed"; }
            else status = "success";
            hooks.recordRun(new HookStore.HookRun(Ulid.next("hr_"), h.id(), event, status,
                    code, truncate(out.toString(), 4000), System.currentTimeMillis()));
            bus.publish(new LobsterEvent(Events.HOOK_FIRED, sessionId,
                    OM.createObjectNode().put("event", event).put("hookId", h.id())
                            .put("status", status).put("exitCode", code), false));
        }
        return new Result(blocked, failed);
    }

    private static int defaultRun(String command, StringBuilder output) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb = os.contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        boolean finished = p.waitFor(5, TimeUnit.SECONDS);
        if (!finished) { p.destroyForcibly(); return -1; }
        return p.exitValue();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
