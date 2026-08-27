package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Workboard 看板：卡片 CRUD + 移动 + 事件历史。 */
class WorkboardStoreTest {

    @TempDir Path tmp;

    private WorkboardStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5000);
        cfg.enforceForeignKeys(true);
        ds.setConfig(cfg);
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "wbtest")) {
            return new WorkboardStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndGetCard() {
        var wb = store(tmp);
        var c = wb.createCard("实现登录", "用户名+密码登录");
        assertEquals("crd_", c.id().substring(0, 4));
        assertEquals("main", c.boardId());
        assertEquals("triage", c.status());
        assertEquals("normal", c.priority());
        assertEquals("实现登录", c.title());
        assertFalse(c.archived());
        assertTrue(c.position() > 0);

        var found = wb.getCard(c.id());
        assertTrue(found.isPresent());
        assertEquals(c.id(), found.get().id());
    }

    @Test
    void moveCardChangesStatus() {
        var wb = store(tmp);
        var c = wb.createCard("task1", "desc");
        wb.moveCard(c.id(), WorkboardStore.Status.TODO, null);
        var moved = wb.getCard(c.id()).orElseThrow();
        assertEquals("todo", moved.status());

        wb.moveCard(c.id(), WorkboardStore.Status.RUNNING, 5.0);
        var running = wb.getCard(c.id()).orElseThrow();
        assertEquals("running", running.status());
        assertEquals(5.0, running.position());

        var events = wb.listEvents(c.id());
        // created + moved(todo) + moved(running) = 3 events
        assertEquals(3, events.size());
        assertEquals("created", events.get(0).kind());
        assertEquals("moved", events.get(1).kind());
        assertEquals("moved", events.get(2).kind());
    }

    @Test
    void updateCard() {
        var wb = store(tmp);
        var c = wb.createCard("old title", "old desc");
        wb.updateCard(c.id(), "new title", "new desc",
                WorkboardStore.Priority.URGENT, "[bug]", null);
        var updated = wb.getCard(c.id()).orElseThrow();
        assertEquals("new title", updated.title());
        assertEquals("new desc", updated.description());
        assertEquals("urgent", updated.priority());
        assertEquals("[bug]", updated.labels());
    }

    @Test
    void assignAgent() {
        var wb = store(tmp);
        wb.jdbc().update("INSERT INTO agent(id,name,kind,role,workspace_dir,db_path,permission_rules,subagent_depth,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,?)",
                "agt_dev01","dev","agent","developer","ws","db.db","{}",System.currentTimeMillis(),System.currentTimeMillis());
        var c = wb.createCard("task", "desc");
        wb.assignAgent(c.id(), "agt_dev01");
        var assigned = wb.getCard(c.id()).orElseThrow();
        assertEquals("agt_dev01", assigned.assignedAgentId());
        var events = wb.listEvents(c.id());
        assertEquals(2, events.size());
        assertEquals("claimed", events.get(1).kind());
        assertEquals("agt_dev01", events.get(1).actor());
    }

    @Test
    void listByStatus() {
        var wb = store(tmp);
        var c1 = wb.createCard("t1", "d1");
        var c2 = wb.createCard("t2", "d2");
        var c3 = wb.createCard("t3", "d3");
        wb.moveCard(c2.id(), WorkboardStore.Status.TODO, null);
        wb.moveCard(c3.id(), WorkboardStore.Status.DONE, null);

        List<WorkboardStore.Card> triage = wb.listByStatus("main", WorkboardStore.Status.TRIAGE);
        assertEquals(1, triage.size());
        assertEquals(c1.id(), triage.get(0).id());

        List<WorkboardStore.Card> todo = wb.listByStatus("main", WorkboardStore.Status.TODO);
        assertEquals(1, todo.size());
        assertEquals(c2.id(), todo.get(0).id());
    }

    @Test
    void archiveAndDelete() {
        var wb = store(tmp);
        var c = wb.createCard("task", "desc");
        wb.archiveCard(c.id());
        assertTrue(wb.getCard(c.id()).orElseThrow().archived());
        assertEquals(0, wb.listByStatus("main", WorkboardStore.Status.TRIAGE).size());
        assertEquals(1, wb.listArchived("main").size());

        wb.deleteCard(c.id());
        assertTrue(wb.getCard(c.id()).isEmpty());
        // 事件历史级联删除
        assertEquals(0, wb.listEvents(c.id()).size());
    }

    @Test
    void positionAutoIncrement() {
        var wb = store(tmp);
        var c1 = wb.createCard("t1", "d1");
        var c2 = wb.createCard("t2", "d2");
        var c3 = wb.createCard("t3", "d3");
        // 同列 triage，position 递增
        assertTrue(c2.position() > c1.position());
        assertTrue(c3.position() > c2.position());
    }
}
