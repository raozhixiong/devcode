package com.lobster.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 事件恢复：SSE 回放 + 断线补拉。 */
@RestController
@RequestMapping("/api/session")
public class EventController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final EventBus bus;

    public EventController(EventBus bus) {
        this.bus = bus;
    }

    /** 历史事件回放（after=0 返回全部）。 */
    @GetMapping("/{id}/events")
    public List<ObjectNode> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return bus.replay(id, after).stream().map(this::toFrame).toList();
    }

    /** SSE 订阅：先回放 after 之后的事件，再持续推送。 */
    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        Runnable unsub = bus.subscribe(id, e -> {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name(e.type()).data(toFrame(e)));
            } catch (Exception ignored) {}
        });
        emitter.onCompletion(unsub);
        emitter.onTimeout(unsub);
        // 回放历史
        try {
            for (LobsterEvent e : bus.replay(id, after)) {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name(e.type()).data(toFrame(e)));
            }
        } catch (Exception ignored) {}
        return emitter;
    }

    private ObjectNode toFrame(LobsterEvent e) {
        ObjectNode frame = OM.createObjectNode();
        frame.put("event", e.type());
        frame.set("payload", e.data());
        if (e.durable()) frame.put("seq", e.seq());
        return frame;
    }
}
