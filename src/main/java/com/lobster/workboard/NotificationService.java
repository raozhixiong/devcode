package com.lobster.workboard;

import com.lobster.event.Events;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.store.ChannelStore;
import com.lobster.store.WorkboardStore;
import com.lobster.ws.ChannelReplyService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通知分发：订阅看板卡片事件，写入通知中心、广播 live 事件，并按订阅把通知外发到渠道
 * （target 形如 channel:wecom:acct）。对齐 OpenClaw notification subscription。
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final java.util.Set<String> BOARD_EVENTS = java.util.Set.of(
            "claimed", "released", "completed", "blocked", "unblocked",
            "heartbeat", "linked", "linked_session", "moved", "commented",
            "proof_added", "attempt_added", "diagnostic");

    private final WorkboardStore wb;
    private final ChannelReplyService channelReply;

    public NotificationService(WorkboardStore wb, EventBus bus, ChannelReplyService channelReply) {
        this.wb = wb;
        this.channelReply = channelReply;
        bus.subscribeAll((LobsterEvent ev) -> {
            try {
                if (!Events.WORKBOARD_CHANGED.equals(ev.type())) return;
                var data = ev.data();
                if (data == null) return;
                String cardId = data.path("cardId").asText();
                String kind = data.path("kind").asText();
                if (cardId.isEmpty() || !BOARD_EVENTS.contains(kind)) return;
                var card = wb.getCard(cardId);
                if (card.isEmpty()) return;
                String msg = summarize(card.get().title(), kind);
                wb.addNotification(cardId, kind, msg);
                deliverExternal(card.get().boardId(), cardId, msg);
                bus.publish(new LobsterEvent("workboard.notification", cardId,
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                .put("kind", kind).put("message", msg), false));
            } catch (RuntimeException ignored) {
                log.debug("通知分发跳过事件 type={}", ev.type());
            }
        });
        log.info("workboard notification 分发已订阅卡片事件");
    }

    private void deliverExternal(String boardId, String cardId, String msg) {
        if (channelReply == null) return;
        for (String target : wb.listSubscriptionTargets(cardId, boardId)) {
            if (target == null || !target.startsWith("channel:")) continue;
            String[] p = target.split(":", 3);
            if (p.length < 3) continue;
            channelReply.sendToChannel(p[1], p[2], msg);
        }
    }

    private static String summarize(String title, String type) {
        return switch (type) {
            case "claimed" -> "卡片「" + title + "」被认领";
            case "released" -> "卡片「" + title + "」已释放";
            case "completed" -> "卡片「" + title + "」已完成";
            case "blocked" -> "卡片「" + title + "」被阻塞";
            case "unblocked" -> "卡片「" + title + "」已解除阻塞";
            case "moved" -> "卡片「" + title + "」已移动";
            case "linked" -> "卡片「" + title + "」新增关联";
            case "commented" -> "卡片「" + title + "」新增评论";
            case "proof_added" -> "卡片「" + title + "」新增证明";
            default -> "卡片「" + title + "」事件：" + type;
        };
    }
}
