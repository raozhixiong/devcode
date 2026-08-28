package com.lobster.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.store.AgentDb;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 双通道事件总线：durable 落库（aggregateId 分配 seq）可回放；live 仅广播。 */
public class EventBus {

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Map<String, CopyOnWriteArrayList<Consumer<LobsterEvent>>> aggregateListeners =
            new ConcurrentHashMap<>();
    private final List<Consumer<LobsterEvent>> globalListeners = new CopyOnWriteArrayList<>();
    private final Object seqLock = new Object();

    public EventBus(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    /** 发布事件：durable 先落库（分配 seq），随后广播。返回事件（含 seq）。 */
    public LobsterEvent publish(LobsterEvent event) {
        LobsterEvent out = event;
        if (event.durable()) {
            long seq = nextSeq(event.aggregateId());
            out = event.withSeq(seq);
            try {
                jdbc.update("INSERT INTO event(id, aggregate_id, seq, type, data, created_at) VALUES(?,?,?,?,?,?)",
                        Ulid.next("evt_"), event.aggregateId(), seq, event.type(),
                        OM.writeValueAsString(event.data()), System.currentTimeMillis());
            } catch (Exception e) {
                throw new IllegalStateException("durable 事件落库失败: " + event.type(), e);
            }
        }
        broadcast(out);
        return out;
    }

    public Runnable subscribe(String aggregateId, Consumer<LobsterEvent> listener) {
        var list = aggregateListeners.computeIfAbsent(aggregateId, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    public Runnable subscribeAll(Consumer<LobsterEvent> listener) {
        globalListeners.add(listener);
        return () -> globalListeners.remove(listener);
    }

    public List<LobsterEvent> replay(String aggregateId, long afterSeq) {
        List<LobsterEvent> out = new ArrayList<>();
        jdbc.query("SELECT type, data, seq FROM event WHERE aggregate_id=? AND seq>? ORDER BY seq",
                rs -> {
                    try {
                        out.add(new LobsterEvent(rs.getString(1), aggregateId,
                                OM.readTree(rs.getString(2)), true, rs.getLong(3)));
                    } catch (Exception e) {
                        throw new IllegalStateException("事件反序列化失败", e);
                    }
                }, aggregateId, afterSeq);
        return out;
    }

    private void broadcast(LobsterEvent event) {
        for (Consumer<LobsterEvent> l : globalListeners) {
            try { l.accept(event); } catch (RuntimeException ignored) {}
        }
        if (event.aggregateId() != null) {
            var list = aggregateListeners.get(event.aggregateId());
            if (list != null) {
                for (Consumer<LobsterEvent> l : list) {
                    try { l.accept(event); } catch (RuntimeException ignored) {}
                }
            }
        }
    }

    private long nextSeq(String aggregateId) {
        synchronized (seqLock) {
            jdbc.update("INSERT OR IGNORE INTO event_sequence(aggregate_id, seq) VALUES(?, 0)", aggregateId);
            jdbc.update("UPDATE event_sequence SET seq = seq + 1 WHERE aggregate_id=?", aggregateId);
            Long seq = jdbc.queryForObject("SELECT seq FROM event_sequence WHERE aggregate_id=?",
                    Long.class, aggregateId);
            return seq == null ? 1 : seq;
        }
    }
}
