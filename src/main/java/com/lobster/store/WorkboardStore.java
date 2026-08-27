package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Workboard 看板（对齐 FR-C2-1/C2-2）：9 列状态看板 + 卡片 CRUD + 事件历史。
 * 操作共享库 workboard_card / workboard_event 表。
 */
public class WorkboardStore {

    private static final ObjectMapper OM = new ObjectMapper();

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

    public record Card(
            String id, String boardId, String status, String priority,
            String labels, String title, String description,
            String assignedAgentId, String assignedUserId,
            String linkedTaskId, String linkedRunId, String linkedSessionKey,
            String execution, double position, String templateId,
            boolean archived, String metadata, long createdAt, long updatedAt) {}

    public record CardEvent(
            String id, String cardId, String kind, String actor, String payload, long createdAt) {}

    /** 创建卡片。 */
    public Card createCard(String boardId, String title, String description,
                           Status status, Priority priority,
                           String assignedAgentId, String assignedUserId,
                           String linkedTaskId, String linkedRunId, String linkedSessionKey,
                           String labels, String metadata) {
        String id = Ulid.next("crd_");
        long now = System.currentTimeMillis();
        double pos = nextPosition(boardId, status);
        jdbc.update("""
                INSERT INTO workboard_card(id, board_id, status, priority, labels, title, description,
                                           assigned_agent_id, assigned_user_id, linked_task_id,
                                           linked_run_id, linked_session_key, execution, position,
                                           template_id, archived, metadata, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)
                """,
                id, boardId, status.name().toLowerCase(), priority.name().toLowerCase(),
                labels, title, description,
                assignedAgentId, assignedUserId, linkedTaskId, linkedRunId, linkedSessionKey,
                null, pos, null, metadata == null ? "{}" : metadata, now, now);
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

    /** 更新卡片字段。 */
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

    /** 指派 agent。 */
    public void assignAgent(String id, String agentId) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET assigned_agent_id=?, updated_at=? WHERE id=?",
                agentId, now, id);
        addEvent(id, "claimed", agentId, null);
        publishChanged(id, "claimed");
    }

    /** 移动卡片到新列（状态变更 + 排序）。 */
    public void moveCard(String id, Status newStatus, Double newPosition) {
        long now = System.currentTimeMillis();
        String boardId = jdbc.queryForObject(
                "SELECT board_id FROM workboard_card WHERE id=?", String.class, id);
        double pos = newPosition != null ? newPosition : nextPosition(boardId, newStatus);
        jdbc.update("UPDATE workboard_card SET status=?, position=?, updated_at=? WHERE id=?",
                newStatus.name().toLowerCase(), pos, now, id);
        addEvent(id, "moved", null,
                OM.createObjectNode().put("status", newStatus.name().toLowerCase()).put("position", pos).toString());
        publishChanged(id, "moved");
    }

    /** 归档卡片。 */
    public void archiveCard(String id) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE workboard_card SET archived=1, updated_at=? WHERE id=?", now, id);
        addEvent(id, "archived", null, null);
        publishChanged(id, "archived");
    }

    /** 删除卡片（级联删除事件）。 */
    public void deleteCard(String id) {
        jdbc.update("DELETE FROM workboard_card WHERE id=?", id);
        publishChanged(id, "deleted");
    }

    /** 添加事件历史。 */
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

    private static final String SELECT_CARD = """
            SELECT id, board_id, status, priority, labels, title, description,
                   assigned_agent_id, assigned_user_id, linked_task_id, linked_run_id,
                   linked_session_key, execution, position, template_id, archived,
                   metadata, created_at, updated_at
            FROM workboard_card
            """;

    private Card mapCard(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Card(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13),
                rs.getDouble(14), rs.getString(15), rs.getInt(16) == 1,
                rs.getString(17), rs.getLong(18), rs.getLong(19));
    }
}
