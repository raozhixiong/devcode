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

    @Test
    void claimCardSetsRunningAndToken() {
        var wb = store(tmp);
        var c = wb.createCard("task", "desc");
        var token = wb.claimCard(c.id(), "agt_dev01", null);
        assertTrue(token.isPresent());
        var claimed = wb.getCard(c.id()).orElseThrow();
        assertEquals("running", claimed.status());
        assertEquals("agt_dev01", claimed.claimOwner());
        assertEquals("running", claimed.executionStatus());
        assertTrue(claimed.claimExpiresAt() > System.currentTimeMillis());

        // 第二次认领同一卡（未过期）失败
        var second = wb.claimCard(c.id(), "agt_dev02", null);
        assertTrue(second.isEmpty());
    }

    @Test
    void completeCardMovesToDoneAndAddsAttempt() {
        var wb = store(tmp);
        var c = wb.createCard("task", "desc");
        wb.claimCard(c.id(), "agt_dev01", null);
        wb.completeCard(c.id(), "全部做完");
        var done = wb.getCard(c.id()).orElseThrow();
        assertEquals("done", done.status());
        assertEquals("done", done.executionStatus());
        assertNotNull(done.completedAt());
        assertEquals(1, wb.listAttempts(c.id()).size());
        assertEquals(WorkboardStore.AttemptStatus.SUCCEEDED, wb.listAttempts(c.id()).get(0).status());
    }

    @Test
    void blockAndUnblock() {
        var wb = store(tmp);
        var c = wb.createCard("task", "desc");
        wb.claimCard(c.id(), "agt_dev01", null);
        wb.blockCard(c.id(), "缺依赖");
        var blocked = wb.getCard(c.id()).orElseThrow();
        assertEquals("blocked", blocked.status());
        wb.unblockCard(c.id());
        assertEquals("todo", wb.getCard(c.id()).orElseThrow().status());
    }

    @Test
    void heartbeatExtendsExpiry() {
        var wb = store(tmp);
        var c = wb.createCard("task", "desc");
        wb.claimCard(c.id(), "agt_dev01", 1000L); // 1 秒后过期
        long before = wb.getCard(c.id()).orElseThrow().claimExpiresAt();
        boolean ok = wb.heartbeatCard(c.id(), wb.getCard(c.id()).orElseThrow().claimToken());
        assertTrue(ok);
        long after = wb.getCard(c.id()).orElseThrow().claimExpiresAt();
        assertTrue(after > before);
    }

    @Test
    void boardsCrud() {
        var wb = store(tmp);
        assertTrue(wb.getBoard("main").isPresent());
        wb.createBoard("proj", "项目板", null, null, null);
        assertEquals(2, wb.listBoards(false).size());
        wb.archiveBoard("proj");
        assertEquals(1, wb.listBoards(false).size());
        assertTrue(wb.listBoards(true).stream().anyMatch(b -> "proj".equals(b.id())));
    }

    @Test
    void linkCardsAndDiagnostics() {
        var wb = store(tmp);
        var parent = wb.createCard("parent", "p");
        var child = wb.createCard("child", "c");
        wb.linkCards(parent.id(), WorkboardStore.LinkType.CHILD, child.id(), null, null);
        assertEquals(1, wb.listLinks(parent.id()).size());

        // 令一张 running 卡片的 claim 过期，应触发诊断
        var stuck = wb.createCard("stuck", "s");
        wb.claimCard(stuck.id(), "agt_x", 1L);
        wb.jdbc().update("UPDATE workboard_card SET claim_expires_at=? WHERE id=?", 1L, stuck.id());
        var diags = wb.detectDiagnostics("main");
        assertTrue(diags.stream().anyMatch(d -> d.cardId().equals(stuck.id())
                && d.kind() == WorkboardStore.DiagnosticKind.RUNNING_WITHOUT_HEARTBEAT));
    }

    @Test
    void decomposeCreatesChildrenAndBlocksParent() {
        var wb = store(tmp);
        var parent = wb.createCard("大任务", "desc");
        var kids = wb.decomposeCard(parent.id(), List.of("子1", "子2", "子3"));
        assertEquals(3, kids.size());
        assertEquals("blocked", wb.getCard(parent.id()).get().status());
        assertEquals("child", wb.listLinks(parent.id()).get(0).type().name().toLowerCase());
        assertEquals("todo", wb.getCard(kids.get(0).id()).get().status());
    }

    @Test
    void completingAllChildrenCompletesParent() {
        var wb = store(tmp);
        var parent = wb.createCard("P", "p");
        var kids = wb.decomposeCard(parent.id(), List.of("a", "b"));
        wb.completeCard(kids.get(0).id(), "done a");
        assertEquals("blocked", wb.getCard(parent.id()).get().status());
        wb.completeCard(kids.get(1).id(), "done b");
        assertEquals("done", wb.getCard(parent.id()).get().status());
    }

    @Test
    void blockingCardCascadesToDependent() {
        var wb = store(tmp);
        var base = wb.createCard("基础", "b");
        var dep = wb.createCard("依赖方", "d");
        wb.moveCard(dep.id(), WorkboardStore.Status.TODO, null);
        wb.linkCards(dep.id(), WorkboardStore.LinkType.BLOCKED_BY, base.id(), null, null);
        wb.blockCard(base.id(), "基础挂了");
        assertEquals("blocked", wb.getCard(dep.id()).get().status());
    }

    @Test
    void notificationRecordedAndListed() {
        var wb = store(tmp);
        var c = wb.createCard("nc", "n");
        wb.addNotification(c.id(), "completed", "卡片已完成");
        var list = wb.listNotifications("main", 10);
        assertEquals(1, list.size());
        assertEquals("completed", list.get(0).kind());
    }

    @Test
    void subscribeStoresTargetAndListsThem() {
        var wb = store(tmp);
        var c = wb.createCard("sc", "s");
        wb.subscribe(c.id(), null, "channel:wecom:acct1", "*");
        wb.subscribe(c.id(), null, "me", "*");
        var targets = wb.listSubscriptionTargets(c.id(), "main");
        assertTrue(targets.contains("channel:wecom:acct1"));
        assertTrue(targets.contains("me"));
    }
}
