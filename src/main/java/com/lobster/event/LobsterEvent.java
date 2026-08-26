package com.lobster.event;

import com.fasterxml.jackson.databind.JsonNode;

/** aggregateId 通常为 sessionID。durable=true 时落库可回放。 */
public record LobsterEvent(String type, String aggregateId, JsonNode data, boolean durable, long seq) {

    public LobsterEvent(String type, String aggregateId, JsonNode data, boolean durable) {
        this(type, aggregateId, data, durable, 0);
    }

    public LobsterEvent withSeq(long seq) {
        return new LobsterEvent(type, aggregateId, data, durable, seq);
    }
}
