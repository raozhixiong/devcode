package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.store.WorkboardStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 看板工具（对齐 OpenClaw board.* 系列）。单类按 action 分发，注册 11 个独立工具：
 * board.list / board.read / board.create / board.move / board.update /
 * board.claim / board.release / board.complete / board.block / board.unblock / board.heartbeat
 *
 * <p>写操作（claim/release/complete/block/unblock/heartbeat）校验持有者：
 * 卡片 claimOwner 必须等于当前 agentId，防止跨 agent 抢占。
 */
public class WorkboardTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();
    private final String action;
    private final WorkboardStore wb;

    public WorkboardTool(String action, WorkboardStore wb) {
        this.action = action;
        this.wb = wb;
    }

    @Override public String id() { return "board." + action; }

    @Override public String description() {
        return switch (action) {
            case "list" -> "列出看板卡片，可按 boardId/status/agent 过滤。";
            case "read" -> "读取单卡详情：含链接/运行历史/评论/证明/诊断。";
            case "create" -> "在看板创建卡片。";
            case "move" -> "移动卡片到新状态列。";
            case "update" -> "更新卡片标题/描述/优先级/标签。";
            case "claim" -> "认领卡片获取独占执行权，状态转 RUNNING，需定期 heartbeat。";
            case "release" -> "释放认领（暂停/交接），状态回退。";
            case "complete" -> "完成卡片并附总结，状态转 DONE。";
            case "block" -> "阻塞卡片并附原因，状态转 BLOCKED。";
            case "unblock" -> "解除阻塞，状态回退 TODO。";
            case "heartbeat" -> "刷新认领心跳防止过期。";
            case "decompose" -> "把卡片拆分为多个子任务（父卡转 BLOCKED 等待，依赖自动联动）。";
            default -> "看板操作 " + action;
        };
    }

    @Override public Map<String, Object> parameters() {
        return switch (action) {
            case "list" -> Map.of("type", "object", "properties", Map.of(
                    "boardId", Map.of("type", "string", "description", "看板 ID，默认 main"),
                    "status", Map.of("type", "string", "description", "过滤状态"),
                    "agentId", Map.of("type", "string", "description", "按认领者过滤")),
                    "required", List.of());
            case "read" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string")), "required", List.of("cardId"));
            case "create" -> {
                var props = new java.util.LinkedHashMap<String, Object>();
                props.put("boardId", Map.of("type", "string"));
                props.put("title", Map.of("type", "string"));
                props.put("description", Map.of("type", "string"));
                props.put("status", Map.of("type", "string"));
                props.put("priority", Map.of("type", "string"));
                props.put("assignedAgentId", Map.of("type", "string"));
                props.put("labels", Map.of("type", "string", "description", "逗号分隔"));
                props.put("templateId", Map.of("type", "string"));
                props.put("sourceUrl", Map.of("type", "string"));
                props.put("linkedTaskId", Map.of("type", "string"));
                props.put("linkedRunId", Map.of("type", "string"));
                props.put("linkedSessionKey", Map.of("type", "string"));
                yield Map.of("type", "object", "properties", props, "required", List.of("title"));
            }
            case "move" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "status", Map.of("type", "string"),
                    "position", Map.of("type", "number")),
                    "required", List.of("cardId", "status"));
            case "update" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "title", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "priority", Map.of("type", "string"),
                    "labels", Map.of("type", "string")),
                    "required", List.of("cardId"));
            case "claim" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "ttlMs", Map.of("type", "number", "description", "认领有效期毫秒，默认 30 分钟")),
                    "required", List.of("cardId"));
            case "release" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "fallback", Map.of("type", "string", "description", "回退状态，默认 todo")),
                    "required", List.of("cardId"));
            case "complete" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "summary", Map.of("type", "string")),
                    "required", List.of("cardId"));
            case "block" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "reason", Map.of("type", "string")),
                    "required", List.of("cardId"));
            case "unblock" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string")), "required", List.of("cardId"));
            case "heartbeat" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string")), "required", List.of("cardId"));
            case "decompose" -> Map.of("type", "object", "properties", Map.of(
                    "cardId", Map.of("type", "string"),
                    "items", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "子任务标题列表")),
                    "required", List.of("cardId", "items"));
            default -> Map.of("type", "object", "properties", Map.of(), "required", List.of());
        };
    }

    @Override public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) throws Exception {
        return switch (action) {
            case "list" -> doList(args);
            case "read" -> doRead(args);
            case "create" -> doCreate(args);
            case "move" -> doMove(args);
            case "update" -> doUpdate(args);
            case "claim" -> doClaim(args, ctx);
            case "release" -> doRelease(args, ctx);
            case "complete" -> doComplete(args, ctx);
            case "block" -> doBlock(args, ctx);
            case "unblock" -> doUnblock(args, ctx);
            case "heartbeat" -> doHeartbeat(args, ctx);
            case "decompose" -> doDecompose(args, ctx);
            default -> ToolResult.of("board." + action, "{\"error\":\"unknown action\"}");
        };
    }

    private ToolResult doList(com.fasterxml.jackson.databind.JsonNode args) {
        String boardId = args.path("boardId").asText("main");
        String status = args.path("status").asText("");
        String agent = args.path("agentId").asText("");
        var cards = status.isEmpty() ? wb.listCards(boardId) : wb.listByStatus(boardId, WorkboardStore.Status.valueOf(status.toUpperCase()));
        if (!agent.isEmpty()) cards = cards.stream().filter(c -> agent.equals(c.claimOwner())).toList();
        ArrayNode arr = OM.createArrayNode();
        for (var c : cards) arr.add(cardJson(c));
        return ToolResult.of("board.list", OM.createObjectNode().put("boardId", boardId).set("cards", arr).toString());
    }

    private ToolResult doRead(com.fasterxml.jackson.databind.JsonNode args) {
        String cardId = args.path("cardId").asText();
        var c = wb.getCard(cardId);
        if (c.isEmpty()) return ToolResult.of("board.read", "{\"error\":\"NOT_FOUND\"}");
        ObjectNode o = cardJson(c.get());
        ArrayNode links = OM.createArrayNode(); for (var l : wb.listLinks(cardId)) links.add(OM.createObjectNode()
                .put("id", l.id()).put("type", l.type().name()).put("targetCardId", l.targetCardId()).put("title", l.title()));
        ArrayNode attempts = OM.createArrayNode(); for (var a : wb.listAttempts(cardId)) attempts.add(OM.createObjectNode()
                .put("id", a.id()).put("status", a.status().name()).put("engine", a.engine()).put("startedAt", a.startedAt()).put("error", a.error()));
        ArrayNode comments = OM.createArrayNode(); for (var cm : wb.listComments(cardId)) comments.add(OM.createObjectNode()
                .put("id", cm.id()).put("body", cm.body()).put("createdAt", cm.createdAt()));
        ArrayNode proofs = OM.createArrayNode(); for (var p : wb.listProof(cardId)) proofs.add(OM.createObjectNode()
                .put("id", p.id()).put("status", p.status().name()).put("label", p.label()));
        String boardId = c.get().boardId();
        ArrayNode diags = OM.createArrayNode(); for (var d : wb.listDiagnostics(boardId).stream().filter(x -> x.cardId().equals(cardId)).toList())
            diags.add(OM.createObjectNode().put("kind", d.kind().name()).put("severity", d.severity().name()).put("title", d.title()));
        o.set("links", links);
        o.set("attempts", attempts);
        o.set("comments", comments);
        o.set("proofs", proofs);
        o.set("diagnostics", diags);
        return ToolResult.of("board.read", o.toString());
    }

    private ToolResult doCreate(com.fasterxml.jackson.databind.JsonNode args) {
        String boardId = args.path("boardId").asText("main");
        String title = args.path("title").asText();
        if (title.isEmpty()) return ToolResult.of("board.create", "{\"error\":\"title required\"}");
        var c = wb.createCard(boardId, title, args.path("description").asText(null),
                parseStatus(args.path("status").asText("triage")),
                parsePriority(args.path("priority").asText("normal")),
                args.path("assignedAgentId").asText(null), null,
                args.path("linkedTaskId").asText(null), args.path("linkedRunId").asText(null),
                args.path("linkedSessionKey").asText(null),
                args.path("labels").asText(null), null,
                args.path("templateId").asText(null), args.path("sourceUrl").asText(null));
        return ToolResult.of("board.create", cardJson(c).toString());
    }

    private ToolResult doMove(com.fasterxml.jackson.databind.JsonNode args) {
        String cardId = args.path("cardId").asText();
        var status = WorkboardStore.Status.valueOf(args.path("status").asText().toUpperCase());
        Double pos = args.has("position") ? args.path("position").asDouble() : null;
        wb.moveCard(cardId, status, pos);
        return ToolResult.of("board.move", cardJson(wb.getCard(cardId).orElseThrow()).toString());
    }

    private ToolResult doUpdate(com.fasterxml.jackson.databind.JsonNode args) {
        String cardId = args.path("cardId").asText();
        var c = wb.getCard(cardId);
        if (c.isEmpty()) return ToolResult.of("board.update", "{\"error\":\"NOT_FOUND\"}");
        wb.updateCard(cardId,
                args.has("title") ? args.path("title").asText() : c.get().title(),
                args.has("description") ? args.path("description").asText() : c.get().description(),
                args.has("priority") ? parsePriority(args.path("priority").asText()) : WorkboardStore.Priority.valueOf(c.get().priority().toUpperCase()),
                args.has("labels") ? args.path("labels").asText() : c.get().labels(),
                null);
        return ToolResult.of("board.update", cardJson(wb.getCard(cardId).orElseThrow()).toString());
    }

    private ToolResult doClaim(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        Long ttl = args.has("ttlMs") ? args.path("ttlMs").asLong() : null;
        var token = wb.claimCard(cardId, ctx.agentId(), ttl);
        if (token.isEmpty()) return ToolResult.of("board.claim", "{\"claimed\":false,\"error\":\"已被认领或不存在\"}");
        return ToolResult.of("board.claim", OM.createObjectNode().put("claimed", true).put("token", token.get())
                .put("cardId", cardId).put("owner", ctx.agentId()).toString());
    }

    private ToolResult doRelease(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        if (!owns(cardId, ctx)) return ToolResult.of("board.release", "{\"ok\":false,\"error\":\"非持有者\"}");
        WorkboardStore.Status fb = args.path("fallback").asText().isEmpty() ? null : WorkboardStore.Status.valueOf(args.path("fallback").asText().toUpperCase());
        wb.releaseCard(cardId, fb);
        return ToolResult.of("board.release", "{\"ok\":true}");
    }

    private ToolResult doComplete(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        if (!owns(cardId, ctx)) return ToolResult.of("board.complete", "{\"ok\":false,\"error\":\"非持有者\"}");
        wb.completeCard(cardId, args.path("summary").asText(null));
        return ToolResult.of("board.complete", "{\"ok\":true}");
    }

    private ToolResult doBlock(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        if (!owns(cardId, ctx)) return ToolResult.of("board.block", "{\"ok\":false,\"error\":\"非持有者\"}");
        wb.blockCard(cardId, args.path("reason").asText(null));
        return ToolResult.of("board.block", "{\"ok\":true}");
    }

    private ToolResult doUnblock(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        wb.unblockCard(args.path("cardId").asText());
        return ToolResult.of("board.unblock", "{\"ok\":true}");
    }

    private ToolResult doHeartbeat(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        var c = wb.getCard(cardId);
        if (c.isEmpty()) return ToolResult.of("board.heartbeat", "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
        // 认领者本人或 token 匹配均可刷新
        boolean ok = (ctx.agentId().equals(c.get().claimOwner()))
                || (!args.path("token").asText().isEmpty() && args.path("token").asText().equals(c.get().claimToken()));
        if (!ok) return ToolResult.of("board.heartbeat", "{\"ok\":false,\"error\":\"非持有者\"}");
        boolean refreshed = wb.heartbeatCard(cardId, c.get().claimToken() == null ? "" : c.get().claimToken());
        return ToolResult.of("board.heartbeat", "{\"ok\":" + refreshed + "}");
    }

    private ToolResult doDecompose(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
        String cardId = args.path("cardId").asText();
        if (wb.getCard(cardId).isEmpty()) return ToolResult.of("board.decompose", "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
        java.util.List<String> items = new java.util.ArrayList<>();
        var arr = args.path("items");
        if (arr.isArray()) {
            arr.forEach(n -> items.add(n.asText()));
        } else if (!arr.asText().isEmpty()) {
            for (String s : arr.asText().split("[\n,]")) if (!s.isBlank()) items.add(s.strip());
        }
        var created = wb.decomposeCard(cardId, items);
        ArrayNode ids = OM.createArrayNode();
        created.forEach(c -> ids.add(c.id()));
        return ToolResult.of("board.decompose",
                OM.createObjectNode().put("ok", true).put("count", created.size()).set("children", ids).toString());
    }

    private boolean owns(String cardId, ToolContext ctx) {
        var c = wb.getCard(cardId);
        return c.isPresent() && ctx.agentId().equals(c.get().claimOwner());
    }

    private ObjectNode cardJson(WorkboardStore.Card c) {
        return OM.createObjectNode()
                .put("id", c.id()).put("boardId", c.boardId()).put("status", c.status())
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
