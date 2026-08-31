package com.lobster.workboard;

import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.event.Events;
import com.lobster.store.AgentDb;
import com.lobster.store.WorkboardStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {

    @TempDir Path tmp;

    @Test
    void boardEventWritesNotification() {
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
        try (AgentDb adb = AgentDb.open(tmp.resolve("agents"), "wbtest")) {
            var bus = new EventBus(adb);
            var wb = new WorkboardStore(new JdbcTemplate(ds), bus);
            new NotificationService(wb, bus, null);
            var card = wb.createCard("通知卡", "desc");
            bus.publish(new LobsterEvent(Events.SESSION_IDLE, card.id(),
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), false));
            // completed 事件才会触发（BOARD_EVENTS 集合）
            wb.completeCard(card.id(), "ok");
            var list = wb.listNotifications("main", 10);
            assertTrue(list.stream().anyMatch(n -> "completed".equals(n.kind())));
        }
    }
}
