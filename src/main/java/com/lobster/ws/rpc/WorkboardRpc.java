package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.WorkboardStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** Workboard 看板 RPC（M4-18）。 */
@Component
public class WorkboardRpc extends BaseRpc {

    private final WorkboardStore workboard;

    public WorkboardRpc(WorkboardStore workboard) { this.workboard = workboard; }

    @Override
    public Set<String> methods() {
        return Set.of("workboard.cards.list", "workboard.cards.create", "workboard.cards.update",
                "workboard.cards.move", "workboard.cards.delete", "workboard.cards.events");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "workboard.cards.list" -> list(id, params);
            case "workboard.cards.create" -> create(id, params);
            case "workboard.cards.update" -> update(id, params);
            case "workboard.cards.move" -> move(id, params);
            case "workboard.cards.delete" -> delete(id, params);
            case "workboard.cards.events" -> events(id, params);
        }
    }

    private void list(String id, JsonNode params) {
        String boardId = params.path("boardId").asText("main");
        String status = params.path("status").asText("");
        var cards = status.isEmpty() ? workboard.listCards(boardId)
                : workboard.listByStatus(boardId, WorkboardStore.Status.valueOf(status.toUpperCase()));
        ArrayNode arr = arr();
        for (var c : cards) arr.add(cardJson(c));
        sendRes(id, true, on().set("cards", arr));
    }

    private void create(String id, JsonNode params) {
        String title = params.path("title").asText();
        if (title.isEmpty()) { sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "title 必填")); return; }
        var c = workboard.createCard(params.path("boardId").asText("main"), title,
                params.path("description").asText(null), parseStatus(params.path("status").asText("triage")),
                parsePriority(params.path("priority").asText("normal")),
                params.path("assignedAgentId").asText(null), params.path("assignedUserId").asText(null),
                params.path("linkedTaskId").asText(null), params.path("linkedRunId").asText(null),
                params.path("linkedSessionKey").asText(null), params.path("labels").asText(null),
                params.path("metadata").asText(null));
        sendRes(id, true, cardJson(c));
    }

    private void update(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        var existing = workboard.getCard(cardId);
        if (existing.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        var c = existing.get();
        workboard.updateCard(cardId,
                params.has("title") ? params.path("title").asText() : c.title(),
                params.has("description") ? params.path("description").asText() : c.description(),
                params.has("priority") ? parsePriority(params.path("priority").asText()) : WorkboardStore.Priority.valueOf(c.priority().toUpperCase()),
                params.has("labels") ? params.path("labels").asText() : c.labels(),
                params.has("metadata") ? params.path("metadata").asText() : c.metadata());
        sendRes(id, true, cardJson(workboard.getCard(cardId).orElseThrow()));
    }

    private void move(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        String statusStr = params.path("status").asText();
        if (cardId.isEmpty() || statusStr.isEmpty()) { sendRes(id, false, on().put("code", "BAD_REQUEST")); return; }
        try {
            var status = WorkboardStore.Status.valueOf(statusStr.toUpperCase());
            Double pos = params.has("position") ? params.path("position").asDouble() : null;
            workboard.moveCard(cardId, status, pos);
            sendRes(id, true, cardJson(workboard.getCard(cardId).orElseThrow()));
        } catch (IllegalArgumentException e) {
            sendRes(id, false, on().put("code", "INVALID_STATUS"));
        }
    }

    private void delete(String id, JsonNode params) {
        workboard.deleteCard(params.path("cardId").asText());
        sendRes(id, true, on().put("deleted", true));
    }

    private void events(String id, JsonNode params) {
        ArrayNode arr = arr();
        for (var e : workboard.listEvents(params.path("cardId").asText())) {
            arr.add(on().put("id", e.id()).put("kind", e.kind()).put("actor", e.actor())
                    .put("payload", e.payload()).put("createdAt", e.createdAt()));
        }
        sendRes(id, true, on().set("events", arr));
    }

    private ObjectNode cardJson(WorkboardStore.Card c) {
        return on().put("id", c.id()).put("boardId", c.boardId()).put("status", c.status())
                .put("priority", c.priority()).put("labels", c.labels()).put("title", c.title())
                .put("description", c.description()).put("assignedAgentId", c.assignedAgentId())
                .put("linkedTaskId", c.linkedTaskId()).put("linkedSessionKey", c.linkedSessionKey())
                .put("position", c.position()).put("archived", c.archived())
                .put("createdAt", c.createdAt()).put("updatedAt", c.updatedAt());
    }

    private WorkboardStore.Status parseStatus(String s) { return WorkboardStore.Status.valueOf(s.toUpperCase()); }
    private WorkboardStore.Priority parsePriority(String s) { return WorkboardStore.Priority.valueOf(s.toUpperCase()); }
}
