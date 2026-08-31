package com.lobster.workboard;

import com.lobster.agent.AgentLoop;
import com.lobster.event.Events;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.MockLlmProvider;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.store.WorkboardStore;
import com.lobster.tool.ToolRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatchServiceTest {

    @TempDir Path tmp;

    private WorkboardStore wb() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("lobster.db"));
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5000);
        cfg.enforceForeignKeys(true);
        ds.setConfig(cfg);
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        AgentDb adb = AgentDb.open(tmp.resolve("agents"), "wbtest");
        return new WorkboardStore(new JdbcTemplate(ds), new EventBus(adb));
    }

    @Test
    void dispatchClaimsReadyCardAndWorkerProtocolViolationBlocks() throws Exception {
        var wb = wb();
        try (AgentDb adb = AgentDb.open(tmp.resolve("agents"), "wbtest")) {
            var bus = new EventBus(adb);
            var store = new MessageStore(adb);
            var llm = new MockLlmProvider(List.of(
                    new LlmEvent.TextDelta("我先看看"),
                    new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1))));
            var engine = new PermissionEngine(List.of(), null);
            var loop = new AgentLoop(store, bus, ToolRegistry.of(), engine, llm, "main", "mock");
            var dispatch = new DispatchService(wb, loop, store, "main", null, null);

            var card = wb.createCard("做X", "desc");
            wb.moveCard(card.id(), WorkboardStore.Status.READY, null);
            assertEquals("ready", wb.getCard(card.id()).get().status());

            dispatch.dispatch();
            for (int i = 0; i < 200 && dispatch.activeWorkers() > 0; i++) Thread.sleep(50);

            var after = wb.getCard(card.id()).orElseThrow();
            assertEquals("blocked", after.status(), "worker 未显式完成应协议违例自动阻塞");
        }
    }

    @Test
    void onWorkerEndedBlocksStillRunningCard() {
        var wb = wb();
        var card = wb.createCard("做Y", "desc");
        wb.claimCard(card.id(), "worker:y", null);
        wb.linkSession(card.id(), "ses_y");
        var dispatch = new DispatchService(wb, null, null, "main", null, null);
        dispatch.onWorkerEnded("ses_y");
        assertEquals("blocked", wb.getCard(card.id()).get().status());
        dispatch.onWorkerEnded("ses_y");
        assertEquals("blocked", wb.getCard(card.id()).get().status());
    }

    @Test
    void reapBlocksExpiredClaim() {
        var wb = wb();
        var card = wb.createCard("做Z", "desc");
        wb.claimCard(card.id(), "worker:z", -100000L);
        var dispatch = new DispatchService(wb, null, null, "main", null, null);
        dispatch.tick();
        assertEquals("blocked", wb.getCard(card.id()).get().status());
    }

    @Test
    void lifecycleSyncSubscribesAndSyncsOnSessionIdle() {
        var wb = wb();
        try (AgentDb adb = AgentDb.open(tmp.resolve("agents"), "wbtest")) {
            var bus = new EventBus(adb);
            var dispatch = new DispatchService(wb, null, null, "main", null, null);
            new LifecycleSyncService(bus, dispatch);

            var card = wb.createCard("做W", "desc");
            wb.claimCard(card.id(), "worker:w", null);
            wb.linkSession(card.id(), "ses_w");

            bus.publish(new LobsterEvent(Events.SESSION_IDLE, "ses_w",
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), false));
            assertEquals("blocked", wb.getCard(card.id()).get().status());
        }
    }

    @Test
    void autoDecomposeBlockedCardViaLlm() {
        var wb = wb();
        var llm = new MockLlmProvider(List.of(
                new LlmEvent.TextDelta("[\"子任务A\", \"子任务B\"]"),
                new LlmEvent.Finish("stop", new LlmEvent.Usage(1, 1))));
        var dispatch = new DispatchService(wb, null, null, "main", llm, "mock");
        var card = wb.createCard("大阻塞任务", "desc");
        wb.blockCard(card.id(), "卡住了");
        dispatch.tick();
        assertEquals(2, wb.listLinks(card.id()).size());
        assertEquals("blocked", wb.getCard(card.id()).get().status());
    }
}
