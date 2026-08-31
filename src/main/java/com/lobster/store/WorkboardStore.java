package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Workboard 看板（对齐 OpenClaw workboard-contract）：9 列状态看板 + 卡片 CRUD +
 * 执行/认领/依赖/运行历史/评论/证明/诊断/通知。操作共享库 workboard_* 表。
 */
public class WorkboardStore {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Claim 默认有效期（毫秒）：30 分钟，靠 heartbeat 续命。 */
    public static final long CLAIM_TTL_MS = 30L * 60 * 1000;
    /** blocked 超过此时长判定 blocked_too_long（毫秒）：24 小时。 */
    public static final long BLOCKED_TOO_LONG_MS = 24L * 60 * 60 * 1000;

    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public WorkboardStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    JdbcTemplate jdbc() { return jdbc; }

    public enum Status {
        TRIAGE, BACKLOG, TODO, SCHEDULED, READY, RUNNING, REVIEW, BLOCKED, DONE
    }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }
    public enum ExecutionStatus { IDLE, RUNNING, REVIEW, BLOCKED, DONE }
    public enum LinkType { PARENT, CHILD, BLOCKS, BLOCKED_BY, RELATES_TO }
    public enum AttemptStatus { RUNNING, SUCCEEDED, FAILED, BLOCKED, STOPPED }
    public enum ProofStatus { PASSED, FAILED, SKIPPED, UNKNOWN }
    public enum DiagnosticKind {
        STRANDED_READY, RUNNING_WITHOUT_HEARTBEAT, BLOCKED_TOO_LONG,
        REPEATED_FAILURES, MISSING_PROOF, ORPHANED_SESSION, ARCHIVED_BUT_ACTIVE
    }
    public enum DiagnosticSeverity { WARNING, ERROR, CRITICAL }

    public record Card(
            String id, String boardId, String status, String priority,
            String labels, String title, String description,
            String assignedAgentId, String assignedUserId,
            String linkedTaskId, String linkedRunId, String linkedSessionKey,
            String execution, String executionStatus,
            String claimToken, String claimOwner, long claimExpiresAt,
            int failureCount, String notes, long startedAt, long completedAt,
            String sourceUrl, String staleJson,
            String templateId, double position, boolean archived,
            String metadata, long createdAt, long updatedAt) {}

    public record Board(
            String id, String name, String description, String icon, String color,
            String defaultWorkspaceJson, String orchestrationJson, String automationJobId,
            long createdAt, long updatedAt, Long archivedAt) {}

    public record CardLink(
            String id, String cardId, LinkType type, String targetCardId,
            String title, String url, long createdAt) {}

    public record CardAttempt(
            String id, String cardId, AttemptStatus status, long startedAt, Long endedAt,
            String engine, String mode, String model, String sessionKey, String runId, String error) {}

    public record CardComment(
            String id, String cardId, String body, long createdAt, Long updatedAt) {}

    public record CardProof(
            String id, String cardId, ProofStatus status, long createdAt,
            String label, String command, String url, String note) {}

    public record CardArtifact(
            String id, String cardId, long createdAt, String label, String url, String path, String mimeType) {}

    public record CardDiagnostic(
            String id, String cardId, DiagnosticKind kind, DiagnosticSeverity severity,
            String title, String detail, long firstSeenAt, long lastSeenAt, int count, String actionsJson) {}

    public record CardAttachment(
            String id, String cardId, long createdAt, String fileName, long byteSize, String mimeType, String note) {}

    public record CardNotification(
            String id, String cardId, String kind, long createdAt, Integer sequence,
            String message, String sessionKey, String runId) {}

    public record NotificationSubscription(
            String id, String boardId, String cardId, String sessionKey, String runId,
            String target, String eventKindsJson, Long lastEventAt, String lastEventId,
            Integer lastEventSeq, String deliveredJson, long createdAt, long updatedAt) {}

    public record CardEvent(
            String id, String cardId, String kind, String actor, String payload, long createdAt) {}

    // ===================== 卡片 CRUD =====================

    public Card createCard(String boardId, String title, String description,
                           Status status, Priority priority,
                           String assignedAgentId, String assignedUserId,
                           String linkedTaskId, String linkedRunId, String linkedSessionKey,
                           String labels, String metadata) {
        return createCard(boardId, title, description, status, priority,
                assignedAgentId, assignedUserId, linkedTaskId, linkedRunId, linkedSessionKey,
                labels, metadata, null, null);
    }

    public Card createCard(String boardId, String title, String description,
                           Status status, Priority priority,
                           String assignedAgentId, String assignedUserId,
                           String linkedTaskId, String linkedRunId, String linkedSessionKey,
                           String labels, String metadata, String templateId, String sourceUrl) {
        String id = Ulid.next("crd_");
        long now = System.currentTimeMillis();
        double pos = nextPosition(boardId, status);
        jdbc.update("""
                INSERT INTO workboard_card(id, board_id, status, priority, labels, title, description,
                                           assigned_agent_id, assigned_user_id, linked_task_id,
                                           linked_run_id, linked_session_key, execution, execution_status,
                                           claim_token, claim_owner, claim_expires_at, failure_count,
                                           notes, started_at, completed_at, source_url, stale_json,
                                           template_id, position, archived, metadata, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,0,?,?,?)
                """,
                id, boardId, status.name().toLowerCase(), priority.name().toLowerCase(),
                labels, title, description,
                assignedAgentId, assignedUserId, linkedTaskId, linkedRunId, linkedSessionKey,
                null, ExecutionStatus.IDLE.name().toLowerCase(),
                null, null, 0L,
                null, null, null, sourceUrl, null,
                templateId, pos, "{}", now, now);
        addEvent(id, "created", null, null);
        publishChanged(id, "created");
        return getCard(id).orElseThrow();
    }

    /** 便捷重载：最小参数。 */
    public Card createCard(String title, String description) {
        return createCard("main", title, description,
                Status.TRIAGE, Priority.NORMAL, null, null, null, null, null, null, null);
    }

    public Optional<Card> getCard(String id) {
        List<Card> rows = jdbc.query(SELECT_CARD + " WHERE id=?", this::mapCard, id);
        return rows.stream().findFirst();
    }

    public List<Card> listCards(String boardId) {
        return jdbc.query(SELECT_CARD + " WHERE board_id=? AND archived=0 ORDER BY status, position",
                this::mapCard, boardId);
    }

    public List<Card> listByStatus(String boardId, Status status) {
        return jdbc.query(SELECT_CARD + " WHERE board_id=? AND status=? AND archived=0 ORDER BY position",
                this::mapCard, boardId, status.name().toLowerCase());
    }

    public List<Card> listArchived(String boardId) {
        return jdbc.query(SELECT_CARD + " WHERE board_id=? AND archived=1 ORDER BY updated_at DESC",
                this::mapCard, boardId);
    }

    public void updateCard(String id, String title, String description,
                           Priority priority, String labels, String metadata) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE workboard_card SET title=?, description=?, priority=?, labels=?, metadata=?, updated_at=?
                WHERE id=?
                """,
                title, description, priority.name().toLowerCase(), labels,
                metadata == null ? "{}" : metadata, now, id);
        addEvent(id, "edited", null, null);
        publishChanged(id, "edited");
    }

    /** 指派 agent（保留原语义）。 */
    public void assignAgent(String id, String agentId) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET assigned_agent_id=?, updated_at=? WHERE id=?",
                agentId, now, id);
        addEvent(id, "claimed", agentId, null);
        publishChanged(id, "claimed");
    }

    public void moveCard(String id, Status newStatus, Double newPosition) {
        long now = System.currentTimeMillis();
        String boardId = jdbc.queryForObject(
                "SELECT board_id FROM workboard_card WHERE id=?", String.class, id);
        double pos = newPosition != null ? newPosition : nextPosition(boardId, newStatus);
        jdbc.update("UPDATE workboard_card SET status=?, position=?, updated_at=? WHERE id=?",
                newStatus.name().toLowerCase(), pos, now, id);
        ObjectNode p = OM.createObjectNode().put("status", newStatus.name().toLowerCase()).put("position", pos);
        addEvent(id, "moved", null, p.toString());
        publishChanged(id, "moved");
    }

    public void archiveCard(String id) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET archived=1, updated_at=? WHERE id=?", now, id);
        addEvent(id, "archived", null, null);
        publishChanged(id, "archived");
    }

    public void deleteCard(String id) {
        jdbc.update("DELETE FROM workboard_card WHERE id=?", id);
        publishChanged(id, "deleted");
    }

    // ===================== Claim 机制 =====================

    /** 认领卡片：生成 token，状态转 RUNNING，记录 owner 与过期时间。返回 claimToken。 */
    public Optional<String> claimCard(String id, String owner, Long ttlMs) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty()) return Optional.empty();
        Card card = c.get();
        if (card.archived()) return Optional.empty();
        if (card.claimToken() != null && card.claimExpiresAt() > System.currentTimeMillis()) {
            return Optional.empty(); // 已被他人持有且未过期
        }
        String token = Ulid.next("clm_");
        long expires = System.currentTimeMillis() + (ttlMs != null ? ttlMs : CLAIM_TTL_MS);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE workboard_card SET claim_token=?, claim_owner=?, claim_expires_at=?,
                    execution_status=?, status=?, started_at=COALESCE(started_at, ?), updated_at=?
                WHERE id=?
                """,
                token, owner, expires, ExecutionStatus.RUNNING.name().toLowerCase(),
                Status.RUNNING.name().toLowerCase(), now, now, id);
        addEvent(id, "claimed", owner, OM.createObjectNode().put("token", token).put("expiresAt", expires).toString());
        publishChanged(id, "claimed");
        return Optional.of(token);
    }

    /** 校验 token 并刷新心跳。成功返回 true。 */
    public boolean heartbeatCard(String id, String token) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty() || token == null) return false;
        Card card = c.get();
        if (!token.equals(card.claimToken())) return false;
        long expires = System.currentTimeMillis() + CLAIM_TTL_MS;
        jdbc.update("UPDATE workboard_card SET claim_expires_at=?, updated_at=? WHERE id=?", expires, expires, id);
        addEvent(id, "heartbeat", card.claimOwner(), null);
        publishChanged(id, "heartbeat");
        return true;
    }

    /** 释放认领（保留状态或回退到指定状态）。 */
    public void releaseCard(String id, Status fallback) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        Status to = fallback != null ? fallback : Status.TODO;
        jdbc.update("""
                UPDATE workboard_card SET claim_token=NULL, claim_owner=NULL, claim_expires_at=0,
                    execution_status=?, status=?, updated_at=? WHERE id=?
                """,
                ExecutionStatus.IDLE.name().toLowerCase(), to.name().toLowerCase(), now, id);
        addEvent(id, "released", c.get().claimOwner(), null);
        publishChanged(id, "released");
    }

    /** 完成卡片：状态 DONE，清 claim，记录完成时间 + 可选 summary。 */
    public void completeCard(String id, String summary) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE workboard_card SET claim_token=NULL, claim_owner=NULL, claim_expires_at=0,
                    execution_status=?, status=?, completed_at=?, notes=COALESCE(?, notes), updated_at=? WHERE id=?
                """,
                ExecutionStatus.DONE.name().toLowerCase(), Status.DONE.name().toLowerCase(), now, summary, now, id);
        addAttempt(id, AttemptStatus.SUCCEEDED, null, null, null, null, null, null);
        cascadeOnComplete(id);
        addEvent(id, "completed", c.get().claimOwner(), summary == null ? null : OM.createObjectNode().put("summary", summary).toString());
        publishChanged(id, "completed");
    }

    /** 阻塞卡片：状态 BLOCKED，清 claim，附原因。 */
    public void blockCard(String id, String reason) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE workboard_card SET claim_token=NULL, claim_owner=NULL, claim_expires_at=0,
                    execution_status=?, status=?, updated_at=? WHERE id=?
                """,
                ExecutionStatus.BLOCKED.name().toLowerCase(), Status.BLOCKED.name().toLowerCase(), now, id);
        addAttempt(id, AttemptStatus.BLOCKED, null, null, null, null, null, reason);
        cascadeBlock(id);
        addEvent(id, "blocked", c.get().claimOwner(), reason == null ? null : OM.createObjectNode().put("reason", reason).toString());
        publishChanged(id, "blocked");
    }

    /** 解除阻塞：回退 TODO。 */
    public void unblockCard(String id) {
        Optional<Card> c = getCard(id);
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET execution_status=?, status=?, updated_at=? WHERE id=?",
                ExecutionStatus.IDLE.name().toLowerCase(), Status.TODO.name().toLowerCase(), now, id);
        addEvent(id, "unblocked", null, null);
        publishChanged(id, "unblocked");
    }

    // ===================== 依赖自动联动 =====================

    /** 反向查链接：target = targetCardId 且 type 匹配的链接（父/依赖方向）。 */
    public List<CardLink> listLinksTo(String targetCardId, LinkType type) {
        return jdbc.query("SELECT id, card_id, type, target_card_id, title, url, created_at FROM workboard_card_links WHERE target_card_id=? AND type=?",
                (rs, i) -> new CardLink(rs.getString(1), rs.getString(2),
                        LinkType.valueOf(rs.getString(3).toUpperCase()), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7)),
                targetCardId, type.name().toLowerCase());
    }

    /** 完成卡片后：父卡子任务全完成 -> 自动完成；依赖本卡的卡依赖全完成 -> 自动解除阻塞。 */
    public void cascadeOnComplete(String id) {
        cascadeOnComplete(id, new java.util.HashSet<>());
    }

    private void cascadeOnComplete(String id, java.util.Set<String> seen) {
        if (!seen.add(id)) return;
        for (CardLink l : listLinksTo(id, LinkType.CHILD)) {
            var p = getCard(l.cardId());
            if (p.isEmpty() || p.get().status().equalsIgnoreCase("done")) continue;
            boolean allDone = listLinks(l.cardId()).stream().filter(x -> x.type() == LinkType.CHILD)
                    .allMatch(x -> getCard(x.targetCardId()).map(c -> c.status().equalsIgnoreCase("done")).orElse(false));
            if (allDone) completeCard(l.cardId(), "子任务全部完成（自动联动）");
        }
        for (CardLink l : listLinksTo(id, LinkType.BLOCKED_BY)) {
            var d = getCard(l.cardId());
            if (d.isEmpty() || !d.get().status().equalsIgnoreCase("blocked")) continue;
            boolean allDone = listLinks(l.cardId()).stream().filter(x -> x.type() == LinkType.BLOCKED_BY)
                    .allMatch(x -> getCard(x.targetCardId()).map(c -> c.status().equalsIgnoreCase("done")).orElse(false));
            if (allDone) unblockCard(l.cardId());
        }
    }

    /** 阻塞卡片后：依赖本卡的卡（未完结）自动级联阻塞。 */
    public void cascadeBlock(String id) {
        cascadeBlock(id, new java.util.HashSet<>());
    }

    private void cascadeBlock(String id, java.util.Set<String> seen) {
        if (!seen.add(id)) return;
        for (CardLink l : listLinksTo(id, LinkType.BLOCKED_BY)) {
            var d = getCard(l.cardId());
            if (d.isEmpty()) continue;
            String st = d.get().status().toLowerCase();
            if (st.equals("blocked") || st.equals("done")) continue;
            blockCard(l.cardId(), "上游依赖被阻塞（自动联动）");
            cascadeBlock(l.cardId(), seen);
        }
    }

    /** 拆分卡片为子任务：每个子任务建 TODO 卡并 LINK CHILD；父卡转为 BLOCKED 等待。 */
    public List<Card> decomposeCard(String parentId, List<String> items) {
        var parent = getCard(parentId);
        if (parent.isEmpty()) return List.of();
        List<Card> created = new java.util.ArrayList<>();
        for (String raw : items) {
            String item = raw == null ? "" : raw.strip();
            if (item.isBlank()) continue;
            var c = createCard(item, "子任务（来自「" + parent.get().title() + "」）");
            moveCard(c.id(), Status.TODO, null);
            linkCards(parentId, LinkType.CHILD, c.id(), item, null);
            created.add(c);
        }
        if (!created.isEmpty()) {
            blockCard(parentId, "已拆分 " + created.size() + " 个子任务，等待完成");
        }
        return created;
    }

    // ===================== 通知 =====================

    /** 写入一条卡片事件通知（供通知中心/订阅分发）。 */
    public void addNotification(String cardId, String kind, String message) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workboard_card_notifications(id, card_id, kind, created_at, message) VALUES(?,?,?,?,?)",
                Ulid.next("ntf_"), cardId, kind, now, message);
    }

    /** 列出通知（可按看板过滤），倒序。 */
    public List<CardNotification> listNotifications(String boardId, int limit) {
        String sql = "SELECT n.id, n.card_id, n.kind, n.created_at, n.message " +
                "FROM workboard_card_notifications n JOIN workboard_card c ON c.id=n.card_id" +
                (boardId == null ? "" : " WHERE c.board_id=?") + " ORDER BY n.created_at DESC LIMIT ?";
        if (boardId == null) {
            return jdbc.query(sql, (rs, i) -> new CardNotification(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getLong(4), null, rs.getString(5), null, null), limit);
        }
        return jdbc.query(sql, (rs, i) -> new CardNotification(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getLong(4), null, rs.getString(5), null, null), boardId, limit);
    }

    /** 订阅某卡（或整板）的事件，target 可为 "channel:wecom:acct" 表示外部分发。 */
    public void subscribe(String cardId, String boardId, String target, String kind) {
        String bid = boardId;
        if (bid == null && cardId != null) {
            var card = getCard(cardId);
            if (card.isEmpty()) return;
            bid = card.get().boardId();
        }
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workboard_notification_subscriptions(id, board_id, card_id, target, event_kinds_json, created_at, updated_at) VALUES(?,?,?,?,?,?,?)",
                Ulid.next("sub_"), bid, cardId, target,
                "[\"" + (kind == null ? "*" : kind) + "\"]", now, now);
    }

    /** 收集某卡/看板的订阅 target（用于外部渠道分发）。 */
    public List<String> listSubscriptionTargets(String cardId, String boardId) {
        var out = new java.util.ArrayList<String>();
        if (cardId != null)
            out.addAll(jdbc.queryForList("SELECT DISTINCT target FROM workboard_notification_subscriptions WHERE card_id=?", String.class, cardId));
        if (boardId != null)
            out.addAll(jdbc.queryForList("SELECT DISTINCT target FROM workboard_notification_subscriptions WHERE board_id=? AND card_id IS NULL", String.class, boardId));
        return out;
    }

    /** 绑定卡片到执行它的会话（dispatch 时写入）。 */
    public void linkSession(String cardId, String sessionKey) {
        Optional<Card> c = getCard(cardId);
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET linked_session_key=?, updated_at=? WHERE id=?",
                sessionKey, now, cardId);
        addEvent(cardId, "linked_session", null,
                OM.createObjectNode().put("session", sessionKey).toString());
        publishChanged(cardId, "linked_session");
    }

    /** 按绑定会话查卡片（lifecycle-sync 用）。 */
    public Optional<Card> findBySessionKey(String sessionKey) {
        return jdbc.query(SELECT_CARD + " WHERE linked_session_key=? AND archived=0",
                this::mapCard, sessionKey).stream().findFirst();
    }

    /** 全部未归档卡片（跨看板，dispatch/reap 用）。 */
    public List<Card> listAllCards() {
        return jdbc.query(SELECT_CARD + " WHERE archived=0 ORDER BY status, position", this::mapCard);
    }

    // ===================== 依赖链 =====================

    public void linkCards(String cardId, LinkType type, String targetCardId, String title, String url) {
        String id = Ulid.next("lnk_");
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO workboard_card_links(id, card_id, type, target_card_id, title, url, created_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, cardId, type.name().toLowerCase(), targetCardId, title, url, now);
        addEvent(cardId, "linked", null, OM.createObjectNode().put("type", type.name().toLowerCase())
                .put("target", targetCardId).toString());
        publishChanged(cardId, "linked");
    }

    public List<CardLink> listLinks(String cardId) {
        return jdbc.query("SELECT id, card_id, type, target_card_id, title, url, created_at FROM workboard_card_links WHERE card_id=?",
                (rs, i) -> new CardLink(rs.getString(1), rs.getString(2),
                        LinkType.valueOf(rs.getString(3).toUpperCase()), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7)), cardId);
    }

    // ===================== 运行历史 =====================

    public void addAttempt(String cardId, AttemptStatus status, String engine, String mode,
                           String model, String sessionKey, String runId, String error) {
        String id = Ulid.next("att_");
        long now = System.currentTimeMillis();
        Long ended = status == AttemptStatus.RUNNING ? null : now;
        jdbc.update("""
                INSERT INTO workboard_card_attempts(id, card_id, status, started_at, ended_at, engine, mode, model, session_key, run_id, error)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, cardId, status.name().toLowerCase(), now, ended, engine, mode, model, sessionKey, runId, error);
        if (status == AttemptStatus.FAILED) {
            jdbc.update("UPDATE workboard_card SET failure_count=failure_count+1, updated_at=? WHERE id=?", now, cardId);
        }
        addEvent(cardId, "attempt_" + status.name().toLowerCase(), null, null);
    }

    public List<CardAttempt> listAttempts(String cardId) {
        return jdbc.query("SELECT id, card_id, status, started_at, ended_at, engine, mode, model, session_key, run_id, error FROM workboard_card_attempts WHERE card_id=? ORDER BY started_at DESC",
                (rs, i) -> new CardAttempt(rs.getString(1), rs.getString(2),
                        AttemptStatus.valueOf(rs.getString(3).toUpperCase()), rs.getLong(4),
                        rs.getLong(5) == 0 ? null : rs.getLong(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), cardId);
    }

    // ===================== 评论 =====================

    public void addComment(String cardId, String body) {
        String id = Ulid.next("cmt_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workboard_card_comments(id, card_id, body, created_at, updated_at) VALUES(?,?,?,?,?)",
                id, cardId, body, now, now);
        addEvent(cardId, "comment_added", null, null);
        publishChanged(cardId, "comment_added");
    }

    public List<CardComment> listComments(String cardId) {
        return jdbc.query("SELECT id, card_id, body, created_at, updated_at FROM workboard_card_comments WHERE card_id=? ORDER BY created_at",
                (rs, i) -> new CardComment(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5) == 0 ? null : rs.getLong(5)), cardId);
    }

    // ===================== 证明 =====================

    public void addProof(String cardId, ProofStatus status, String label, String command, String url, String note) {
        String id = Ulid.next("prf_");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workboard_card_proof(id, card_id, status, created_at, label, command, url, note) VALUES(?,?,?,?,?,?,?,?)",
                id, cardId, status.name().toLowerCase(), now, label, command, url, note);
        addEvent(cardId, "proof_added", null, OM.createObjectNode().put("status", status.name().toLowerCase()).toString());
        publishChanged(cardId, "proof_added");
    }

    public List<CardProof> listProof(String cardId) {
        return jdbc.query("SELECT id, card_id, status, created_at, label, command, url, note FROM workboard_card_proof WHERE card_id=? ORDER BY created_at",
                (rs, i) -> new CardProof(rs.getString(1), rs.getString(2),
                        ProofStatus.valueOf(rs.getString(3).toUpperCase()), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), cardId);
    }

    // ===================== 诊断 =====================

    /** 扫描全板生成诊断。返回本板所有诊断（含历史未消除项）。 */
    public List<CardDiagnostic> detectDiagnostics(String boardId) {
        long now = System.currentTimeMillis();
        List<Card> cards = listCards(boardId);
        for (Card c : cards) {
            if (ExecutionStatus.RUNNING.name().equalsIgnoreCase(c.executionStatus())
                    && c.claimExpiresAt() > 0 && c.claimExpiresAt() < now) {
                upsertDiagnostic(c.id(), DiagnosticKind.RUNNING_WITHOUT_HEARTBEAT, DiagnosticSeverity.WARNING,
                        "心跳过期", "认领后超过 " + (CLAIM_TTL_MS / 60000) + " 分钟未续心跳");
            }
            if (Status.BLOCKED == Status.valueOf(c.status().toUpperCase())
                    && c.updatedAt() > 0 && (now - c.updatedAt()) > BLOCKED_TOO_LONG_MS) {
                upsertDiagnostic(c.id(), DiagnosticKind.BLOCKED_TOO_LONG, DiagnosticSeverity.ERROR,
                        "阻塞过久", "卡片阻塞超过 " + (BLOCKED_TOO_LONG_MS / 3600000) + " 小时");
            }
            if (c.failureCount() >= 3) {
                upsertDiagnostic(c.id(), DiagnosticKind.REPEATED_FAILURES, DiagnosticSeverity.ERROR,
                        "反复失败", "失败计数 " + c.failureCount());
            }
            if (Status.DONE == Status.valueOf(c.status().toUpperCase()) && listProof(c.id()).isEmpty()) {
                upsertDiagnostic(c.id(), DiagnosticKind.MISSING_PROOF, DiagnosticSeverity.WARNING,
                        "缺少证明", "已完成但无 proof 记录");
            }
        }
        return listDiagnostics(boardId);
    }

    private void upsertDiagnostic(String cardId, DiagnosticKind kind, DiagnosticSeverity sev,
                                  String title, String detail) {
        String existing = jdbc.query(
                "SELECT id FROM workboard_card_diagnostics WHERE card_id=? AND kind=?",
                (rs, i) -> rs.getString(1), cardId, kind.name().toLowerCase())
                .stream().findFirst().orElse(null);
        long now = System.currentTimeMillis();
        if (existing == null) {
            jdbc.update("INSERT INTO workboard_card_diagnostics(id, card_id, kind, severity, title, detail, first_seen_at, last_seen_at, count) VALUES(?,?,?,?,?,?,?,?,1)",
                    Ulid.next("dia_"), cardId, kind.name().toLowerCase(), sev.name().toLowerCase(), title, detail, now, now);
        } else {
            jdbc.update("UPDATE workboard_card_diagnostics SET last_seen_at=?, count=count+1, detail=? WHERE id=?",
                    now, detail, existing);
        }
    }

    public List<CardDiagnostic> listDiagnostics(String boardId) {
        return jdbc.query("""
                SELECT d.id, d.card_id, d.kind, d.severity, d.title, d.detail, d.first_seen_at, d.last_seen_at, d.count, d.actions_json
                FROM workboard_card_diagnostics d JOIN workboard_card c ON c.id=d.card_id
                WHERE c.board_id=? ORDER BY d.severity, d.last_seen_at DESC
                """,
                (rs, i) -> new CardDiagnostic(rs.getString(1), rs.getString(2),
                        DiagnosticKind.valueOf(rs.getString(3).toUpperCase()),
                        DiagnosticSeverity.valueOf(rs.getString(4).toUpperCase()),
                        rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8),
                        rs.getInt(9), rs.getString(10)), boardId);
    }

    // ===================== 多看板 =====================

    public List<Board> listBoards(boolean includeArchived) {
        String sql = "SELECT id, name, description, icon, color, default_workspace_json, orchestration_json, automation_job_id, created_at, updated_at, archived_at FROM workboard_boards"
                + (includeArchived ? "" : " WHERE archived_at IS NULL") + " ORDER BY created_at";
        return jdbc.query(sql, this::mapBoard);
    }

    public Optional<Board> getBoard(String id) {
        return jdbc.query("SELECT id, name, description, icon, color, default_workspace_json, orchestration_json, automation_job_id, created_at, updated_at, archived_at FROM workboard_boards WHERE id=?",
                this::mapBoard, id).stream().findFirst();
    }

    public Board createBoard(String id, String name, String description, String icon, String color) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workboard_boards(id, name, description, icon, color, created_at, updated_at) VALUES(?,?,?,?,?,?,?)",
                id, name, description, icon, color, now, now);
        publishChangedBoards("created");
        return getBoard(id).orElseThrow();
    }

    public void archiveBoard(String id) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_boards SET archived_at=?, updated_at=? WHERE id=?", now, now, id);
        publishChangedBoards("archived");
    }

    private Board mapBoard(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long arch = rs.getLong(11);
        Long archivedAt = rs.wasNull() ? null : arch;
        return new Board(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getLong(9), rs.getLong(10), archivedAt);
    }

    // ===================== 事件 =====================

    public void addEvent(String cardId, String kind, String actor, String payload) {
        jdbc.update("INSERT INTO workboard_event(id, card_id, kind, actor, payload, created_at) VALUES(?,?,?,?,?,?)",
                Ulid.next("wev_"), cardId, kind, actor,
                payload == null ? "{}" : payload, System.currentTimeMillis());
    }

    public List<CardEvent> listEvents(String cardId) {
        return jdbc.query("SELECT id, card_id, kind, actor, payload, created_at FROM workboard_event "
                        + "WHERE card_id=? ORDER BY created_at",
                (rs, i) -> new CardEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)),
                cardId);
    }

    private double nextPosition(String boardId, Status status) {
        Double max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(position), 0) FROM workboard_card WHERE board_id=? AND status=?",
                Double.class, boardId, status.name().toLowerCase());
        return (max == null ? 0 : max) + 1.0;
    }

    private void publishChanged(String cardId, String kind) {
        ObjectNode data = OM.createObjectNode().put("cardId", cardId).put("kind", kind);
        bus.publish(new LobsterEvent(Events.WORKBOARD_CHANGED, "", data, false));
    }

    private void publishChangedBoards(String kind) {
        ObjectNode data = OM.createObjectNode().put("kind", kind);
        bus.publish(new LobsterEvent(Events.WORKBOARD_CHANGED, "", data, false));
    }

    private static final String SELECT_CARD = """
            SELECT id, board_id, status, priority, labels, title, description,
                   assigned_agent_id, assigned_user_id, linked_task_id, linked_run_id,
                   linked_session_key, execution, execution_status, claim_token, claim_owner,
                   claim_expires_at, failure_count, notes, started_at, completed_at, source_url,
                   stale_json, template_id, position, archived, metadata, created_at, updated_at
            FROM workboard_card
            """;

    private Card mapCard(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Card(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13),
                rs.getString(14) == null ? ExecutionStatus.IDLE.name().toLowerCase() : rs.getString(14),
                rs.getString(15), rs.getString(16),
                rs.getLong(17), rs.getInt(18),
                rs.getString(19), rs.getLong(20), rs.getLong(21),
                rs.getString(22), rs.getString(23), rs.getString(24),
                rs.getDouble(25), rs.getInt(26) == 1,
                rs.getString(27), rs.getLong(28), rs.getLong(29));
    }
}
