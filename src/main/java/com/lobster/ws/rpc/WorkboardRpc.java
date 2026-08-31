package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.WorkboardStore;
import com.lobster.workboard.DispatchService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** Workboard 看板 RPC（对齐 OpenClaw workboard-contract，扩展 claim/boards/dispatch 等）。 */
@Component
public class WorkboardRpc extends BaseRpc {

    private final WorkboardStore workboard;
    private final DispatchService dispatch;

    public WorkboardRpc(WorkboardStore workboard, DispatchService dispatch) {
        this.workboard = workboard;
        this.dispatch = dispatch;
    }

    @Override
    public Set<String> methods() {
        return Set.of("workboard.cards.list", "workboard.cards.read", "workboard.cards.create",
                "workboard.cards.update", "workboard.cards.move", "workboard.cards.delete",
                "workboard.cards.events", "workboard.cards.claim", "workboard.cards.release",
                "workboard.cards.complete", "workboard.cards.block", "workboard.cards.unblock",
                "workboard.cards.heartbeat", "workboard.boards.list", "workboard.boards.create",
                "workboard.boards.archive", "workboard.diagnostics.list", "workboard.dispatch",
                "workboard.cards.decompose", "workboard.notifications.list", "workboard.subscribe");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "workboard.cards.list" -> list(id, params);
            case "workboard.cards.read" -> read(id, params);
            case "workboard.cards.create" -> create(id, params);
            case "workboard.cards.update" -> update(id, params);
            case "workboard.cards.move" -> move(id, params);
            case "workboard.cards.delete" -> delete(id, params);
            case "workboard.cards.events" -> events(id, params);
            case "workboard.cards.claim" -> claim(id, params);
            case "workboard.cards.release" -> release(id, params);
            case "workboard.cards.complete" -> complete(id, params);
            case "workboard.cards.block" -> block(id, params);
            case "workboard.cards.unblock" -> unblock(id, params);
            case "workboard.cards.heartbeat" -> heartbeat(id, params);
            case "workboard.boards.list" -> boardsList(id, params);
            case "workboard.boards.create" -> boardsCreate(id, params);
            case "workboard.boards.archive" -> boardsArchive(id, params);
            case "workboard.diagnostics.list" -> diagnosticsList(id, params);
            case "workboard.dispatch" -> dispatchNow(id);
            case "workboard.cards.decompose" -> decompose(id, params);
            case "workboard.notifications.list" -> notifications(id, params);
            case "workboard.subscribe" -> subscribe(id, params);
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

    private void read(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        var c = workboard.getCard(cardId);
        if (c.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        ObjectNode o = cardJson(c.get());
        ArrayNode links = arr(); for (var l : workboard.listLinks(cardId)) links.add(on().put("id", l.id()).put("type", l.type().name()).put("targetCardId", l.targetCardId()).put("title", l.title()));
        ArrayNode attempts = arr(); for (var a : workboard.listAttempts(cardId)) attempts.add(on().put("id", a.id()).put("status", a.status().name()).put("engine", a.engine()).put("startedAt", a.startedAt()).put("error", a.error()));
        ArrayNode comments = arr(); for (var cm : workboard.listComments(cardId)) comments.add(on().put("id", cm.id()).put("body", cm.body()).put("createdAt", cm.createdAt()));
        ArrayNode proofs = arr(); for (var p : workboard.listProof(cardId)) proofs.add(on().put("id", p.id()).put("status", p.status().name()).put("label", p.label()));
        ArrayNode diags = arr(); for (var d : workboard.listDiagnostics(c.get().boardId()).stream().filter(x -> x.cardId().equals(cardId)).toList())
            diags.add(on().put("kind", d.kind().name()).put("severity", d.severity().name()).put("title", d.title()));
        o.set("links", links);
        o.set("attempts", attempts);
        o.set("comments", comments);
        o.set("proofs", proofs);
        o.set("diagnostics", diags);
        ArrayNode evs = arr(); for (var e : workboard.listEvents(cardId)) evs.add(on().put("kind", e.kind()).put("actor", e.actor()).put("createdAt", e.createdAt()).put("payload", e.payload()));
        o.set("events", evs);
        sendRes(id, true, o);
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
                params.path("metadata").asText(null), params.path("templateId").asText(null), params.path("sourceUrl").asText(null));
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

    private void claim(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        String actor = params.path("actor").asText("user");
        Long ttl = params.has("ttlMs") ? params.path("ttlMs").asLong() : null;
        var token = workboard.claimCard(cardId, actor, ttl);
        if (token.isEmpty()) { sendRes(id, false, on().put("code", "CONFLICT").put("message", "已被认领或不存在")); return; }
        sendRes(id, true, on().put("claimed", true).put("token", token.get()).put("cardId", cardId).put("owner", actor));
    }

    private void release(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        WorkboardStore.Status fb = params.path("fallback").asText().isEmpty() ? null : WorkboardStore.Status.valueOf(params.path("fallback").asText().toUpperCase());
        workboard.releaseCard(cardId, fb);
        sendRes(id, true, on().put("ok", true));
    }

    private void complete(String id, JsonNode params) {
        workboard.completeCard(params.path("cardId").asText(), params.path("summary").asText(null));
        sendRes(id, true, on().put("ok", true));
    }

    private void block(String id, JsonNode params) {
        workboard.blockCard(params.path("cardId").asText(), params.path("reason").asText(null));
        sendRes(id, true, on().put("ok", true));
    }

    private void unblock(String id, JsonNode params) {
        workboard.unblockCard(params.path("cardId").asText());
        sendRes(id, true, on().put("ok", true));
    }

    private void heartbeat(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        var c = workboard.getCard(cardId);
        if (c.isEmpty()) { sendRes(id, false, on().put("code", "NOT_FOUND")); return; }
        String token = params.path("token").asText();
        boolean ok = (!token.isEmpty() && token.equals(c.get().claimToken()))
                || params.path("actor").asText("").equals(c.get().claimOwner());
        if (!ok) { sendRes(id, false, on().put("code", "FORBIDDEN")); return; }
        boolean refreshed = workboard.heartbeatCard(cardId, c.get().claimToken() == null ? "" : c.get().claimToken());
        sendRes(id, true, on().put("ok", refreshed));
    }

    private void boardsList(String id, JsonNode params) {
        boolean inc = params.path("includeArchived").asBoolean(false);
        ArrayNode arr = arr();
        for (var b : workboard.listBoards(inc)) arr.add(on().put("id", b.id()).put("name", b.name())
                .put("icon", b.icon()).put("color", b.color()).put("archivedAt", b.archivedAt() == null ? 0 : b.archivedAt()));
        sendRes(id, true, on().set("boards", arr));
    }

    private void boardsCreate(String id, JsonNode params) {
        String bid = params.path("id").asText();
        if (bid.isEmpty()) { sendRes(id, false, on().put("code", "BAD_REQUEST")); return; }
        if (workboard.getBoard(bid).isPresent()) { sendRes(id, false, on().put("code", "CONFLICT")); return; }
        var b = workboard.createBoard(bid, params.path("name").asText(bid), params.path("description").asText(null),
                params.path("icon").asText(null), params.path("color").asText(null));
        sendRes(id, true, on().put("id", b.id()).put("name", b.name()));
    }

    private void boardsArchive(String id, JsonNode params) {
        workboard.archiveBoard(params.path("id").asText());
        sendRes(id, true, on().put("ok", true));
    }

    private void diagnosticsList(String id, JsonNode params) {
        String boardId = params.path("boardId").asText("main");
        ArrayNode arr = arr();
        for (var d : workboard.detectDiagnostics(boardId)) {
            arr.add(on().put("cardId", d.cardId()).put("kind", d.kind().name())
                    .put("severity", d.severity().name()).put("title", d.title()).put("detail", d.detail()));
        }
        sendRes(id, true, on().put("boardId", boardId).set("diagnostics", arr));
    }

    private void dispatchNow(String id) {
        dispatch.tick();
        sendRes(id, true,
                on().put("ok", true).put("activeWorkers", dispatch.activeWorkers()));
    }

    private void decompose(String id, JsonNode params) {
        String cardId = params.path("cardId").asText();
        java.util.List<String> items = new java.util.ArrayList<>();
        var arr = params.path("items");
        if (arr.isArray()) arr.forEach(n -> items.add(n.asText()));
        else if (!arr.asText().isEmpty())
            for (String s : arr.asText().split("[\n,]")) if (!s.isBlank()) items.add(s.strip());
        var created = workboard.decomposeCard(cardId, items);
        ArrayNode ids = arr();
        created.forEach(c -> ids.add(c.id()));
        sendRes(id, true, on().put("ok", true).put("count", created.size()).set("children", ids));
    }

    private void notifications(String id, JsonNode params) {
        String boardId = params.path("boardId").asText(null);
        int limit = params.path("limit").asInt(50);
        ArrayNode out = arr();
        for (var n : workboard.listNotifications(boardId, limit)) {
            var card = workboard.getCard(n.cardId());
            out.add(on().put("id", n.id()).put("cardId", n.cardId()).put("kind", n.kind())
                    .put("message", n.message())
                    .put("cardTitle", card.map(c -> c.title()).orElse(n.cardId()))
                    .put("createdAt", n.createdAt()));
        }
        sendRes(id, true, on().put("boardId", boardId == null ? "*" : boardId).set("notifications", out));
    }

    private void subscribe(String id, JsonNode params) {
        String cardId = params.path("cardId").asText(null);
        String boardId = params.path("boardId").asText(null);
        String target = params.path("target").asText();
        String kind = params.path("kind").asText(null);
        if (target.isBlank()) { sendRes(id, false, on().put("error", "target 必填")); return; }
        workboard.subscribe(cardId, boardId, target, kind);
        sendRes(id, true, on().put("ok", true));
    }

    private ObjectNode cardJson(WorkboardStore.Card c) {
        return on().put("id", c.id()).put("boardId", c.boardId()).put("status", c.status())
                .put("priority", c.priority()).put("labels", c.labels()).put("title", c.title())
                .put("description", c.description()).put("assignedAgentId", c.assignedAgentId())
                .put("claimOwner", c.claimOwner()).put("executionStatus", c.executionStatus())
                .put("failureCount", c.failureCount()).put("notes", c.notes())
                .put("linkedTaskId", c.linkedTaskId()).put("linkedSessionKey", c.linkedSessionKey())
                .put("templateId", c.templateId()).put("sourceUrl", c.sourceUrl())
                .put("claimExpiresAt", c.claimExpiresAt()).put("startedAt", c.startedAt())
                .put("completedAt", c.completedAt()).put("position", c.position())
                .put("archived", c.archived()).put("createdAt", c.createdAt()).put("updatedAt", c.updatedAt());
    }

    private WorkboardStore.Status parseStatus(String s) { return WorkboardStore.Status.valueOf(s.toUpperCase()); }
    private WorkboardStore.Priority parsePriority(String s) { return WorkboardStore.Priority.valueOf(s.toUpperCase()); }
}
