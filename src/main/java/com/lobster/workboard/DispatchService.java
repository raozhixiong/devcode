package com.lobster.workboard;

import com.lobster.agent.AgentLoop;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.store.WorkboardStore;
import com.lobster.util.Ulid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动派发：扫描 READY 卡片 -> claim -> 起 subagent 会话执行；worker 协议违例（结束未显式
 * complete/block）或心跳过期则自动 block。对齐 OpenClaw dispatcher：默认最多 3 个并发 worker。
 */
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    public static final int MAX_WORKERS = 3;
    private static final long INITIAL_DELAY_MS = 30_000;
    private static final long PERIOD_MS = 30_000;

    private final WorkboardStore wb;
    private final AgentLoop loop;
    private final MessageStore store;
    private final String agentId;
    private final com.lobster.llm.LlmProvider llm;
    private final String model;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Set<String> decomposed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "workboard-dispatcher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean started = new AtomicBoolean(false);

    public DispatchService(WorkboardStore wb, AgentLoop loop, MessageStore store, String agentId,
                           com.lobster.llm.LlmProvider llm, String model) {
        this.wb = wb;
        this.loop = loop;
        this.store = store;
        this.agentId = agentId;
        this.llm = llm;
        this.model = model;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        sched.scheduleWithFixedDelay(this::tick, INITIAL_DELAY_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        log.info("workboard dispatcher 启动：maxWorkers={}", MAX_WORKERS);
    }

    public void stop() {
        sched.shutdownNow();
    }

    /** 单轮调度：先收割过期，再派发 ready，最后对阻塞卡做 LLM 自动拆分提议。 */
    public synchronized void tick() {
        try {
            reap();
            dispatch();
            autoDecomposeCandidates();
        } catch (RuntimeException e) {
            log.warn("dispatch tick 异常", e);
        }
    }

    /** LLM 自主拆分：扫描仍 BLOCKED 且无子任务的卡，调用 LLM 提议子任务并 decompose。 */
    private void autoDecomposeCandidates() {
        if (llm == null) return;
        for (WorkboardStore.Card c : wb.listAllCards()) {
            if (!"blocked".equalsIgnoreCase(c.status())) continue;
            if (decomposed.contains(c.id())) continue;
            decomposed.add(c.id());
            if (wb.listLinks(c.id()).stream().anyMatch(l -> l.type() == WorkboardStore.LinkType.CHILD)) continue;
            var items = proposeSubtasks(c);
            if (!items.isEmpty()) {
                wb.decomposeCard(c.id(), items);
                log.info("auto-decompose 卡片 {} -> {} 个子任务", c.id(), items.size());
            }
        }
    }

    private java.util.List<String> proposeSubtasks(WorkboardStore.Card c) {
        String prompt = "将以下任务拆为 2-5 个独立可执行的子任务。仅输出 JSON 字符串数组（如 [\"子任务1\",\"子任务2\"]），不要任何解释或 markdown。\n任务："
                + (c.title() == null ? "" : c.title())
                + "\n" + (c.description() == null ? "" : c.description());
        StringBuilder sb = new StringBuilder();
        try {
            var req = new com.lobster.llm.LlmProvider.LlmRequest(model, "你是任务分解助手，输出严格 JSON。",
                    java.util.List.of(com.lobster.llm.LlmProvider.ChatMsg.user(prompt)),
                    java.util.List.of(), 0.3);
            llm.stream(req).forEach(e -> {
                if (e instanceof com.lobster.llm.LlmEvent.TextDelta d) sb.append(d.text());
            });
        } catch (RuntimeException ex) {
            log.debug("auto-decompose LLM 调用失败 card={}", c.id(), ex);
            return java.util.List.of();
        }
        return parseJsonList(sb.toString());
    }

    private java.util.List<String> parseJsonList(String text) {
        if (text == null || text.isBlank()) return java.util.List.of();
        String t = text.trim();
        int s = t.indexOf('['), e = t.lastIndexOf(']');
        if (s < 0 || e < 0 || e <= s) return java.util.List.of();
        t = t.substring(s, e + 1);
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t);
            if (!arr.isArray()) return java.util.List.of();
            var out = new java.util.ArrayList<String>();
            arr.forEach(n -> { if (!n.asText().isBlank()) out.add(n.asText()); });
            return out;
        } catch (Exception ex) {
            return java.util.List.of();
        }
    }

    public int activeWorkers() {
        return active.size();
    }

    /** 派发：遍历所有看板的 READY 卡，并发上限内认领并起 worker。 */
    public void dispatch() {
        long now = System.currentTimeMillis();
        for (WorkboardStore.Card c : wb.listAllCards()) {
            if (active.size() >= MAX_WORKERS) break;
            if (!"ready".equalsIgnoreCase(c.status())) continue;
            if (c.claimToken() != null && c.claimExpiresAt() > now) continue;
            if (c.linkedSessionKey() != null && active.contains(c.linkedSessionKey())) continue;
            claimAndRun(c);
        }
    }

    private void claimAndRun(WorkboardStore.Card c) {
        var token = wb.claimCard(c.id(), "worker:" + c.id(), null);
        if (token.isEmpty()) return;
        String title = c.title() == null ? "" : c.title();
        var s = store.createSession("workboard:" + title, "workboard", System.getProperty("user.dir"));
        String sid = s.id();
        wb.linkSession(c.id(), sid);
        active.add(sid);
        String prompt = "请完成看板卡片「" + title + "」" +
                (c.description() != null ? "：" + c.description() : "") +
                "。完成后必须调用 board.complete；若无法完成或需阻塞，调用 board.block。";
        store.appendUser(sid, List.of(new Part.Text(prompt, false, false)));
        log.info("dispatch 起 worker session={} card={}", sid, c.id());
        Thread.startVirtualThread(() -> {
            try {
                loop.run(sid);
            } catch (RuntimeException e) {
                log.warn("worker 运行异常 session={} card={}", sid, c.id(), e);
            } finally {
                onWorkerEnded(sid);
            }
        });
    }

    /** worker 会话结束后的生命周期联动：仍 RUNNING 视为协议违例，自动 block。 */
    public void onWorkerEnded(String sessionKey) {
        active.remove(sessionKey);
        wb.findBySessionKey(sessionKey).ifPresent(c -> {
            if ("running".equalsIgnoreCase(c.status())) {
                wb.blockCard(c.id(), "worker 协议违例：结束未显式 complete/block");
                log.info("worker 协议违例自动 block card={} session={}", c.id(), sessionKey);
            }
        });
    }

    /** 收割心跳过期的被认领卡片。 */
    private void reap() {
        long now = System.currentTimeMillis();
        for (WorkboardStore.Card c : wb.listAllCards()) {
            if (!"running".equalsIgnoreCase(c.executionStatus())) continue;
            if (c.claimExpiresAt() > 0 && c.claimExpiresAt() < now) {
                wb.blockCard(c.id(), "stale：认领心跳过期");
                active.remove(c.linkedSessionKey());
                log.info("stale 自动 block card={}", c.id());
            }
        }
    }
}
