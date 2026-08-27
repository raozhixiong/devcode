package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cron 调度器（对齐 FR-C3-2）：heartbeat 定时扫描 cron_job 表，
 * 到点的 job 自动创建会话 + 触发 AgentLoop + 记录运行历史。
 */
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_SECONDS = 5;

    private final CronStore cronStore;
    private final MessageStore messageStore;
    private final AgentLoop loop;
    private final EventBus bus;
    private final ScheduledExecutorService scheduler;

    public CronScheduler(CronStore cronStore, MessageStore messageStore, AgentLoop loop, EventBus bus) {
        this.cronStore = cronStore;
        this.messageStore = messageStore;
        this.loop = loop;
        this.bus = bus;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cron-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动 heartbeat。 */
    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Cron heartbeat 启动（间隔 {}s）", HEARTBEAT_INTERVAL_SECONDS);
    }

    /** 停止 heartbeat。 */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        log.info("Cron heartbeat 已停止");
    }

    /** 执行一次扫描。 */
    void tick() {
        try {
            List<CronStore.DueJob> due = cronStore.tick();
            for (var job : due) {
                triggerJob(job);
            }
        } catch (Exception e) {
            log.warn("Cron tick 异常: {}", e.getMessage());
        }
    }

    /** 触发单个 job：创建会话 + 追加 prompt + 启动 loop。 */
    private void triggerJob(CronStore.DueJob job) {
        try {
            String sessionKey = "cron-" + job.jobId() + "-" + System.currentTimeMillis();
            var session = messageStore.createSession(sessionKey, job.agentId(),
                    System.getProperty("user.dir"));
            messageStore.appendUser(session.id(), List.of(new Part.Text(job.prompt(), false, false)));

            ObjectNode evtData = OM.createObjectNode()
                    .put("jobId", job.jobId())
                    .put("runId", job.runId())
                    .put("sessionKey", sessionKey)
                    .put("prompt", job.prompt());
            bus.publish(new LobsterEvent(Events.CRON_CHANGED, session.id(), evtData, true));

            Thread.ofVirtual().name("cron-agent-" + session.id()).start(() -> {
                try {
                    loop.run(session.id());
                    cronStore.finishRun(job.runId(), "succeeded", session.id(), null);
                } catch (Exception e) {
                    cronStore.finishRun(job.runId(), "failed", session.id(), e.getMessage());
                    log.warn("Cron job {} 运行失败: {}", job.jobId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            cronStore.finishRun(job.runId(), "failed", null, e.getMessage());
            log.warn("Cron job {} 触发失败: {}", job.jobId(), e.getMessage());
        }
    }
}
