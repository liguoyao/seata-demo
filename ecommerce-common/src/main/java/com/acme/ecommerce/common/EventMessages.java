package com.acme.ecommerce.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class EventMessages {
    private EventMessages() {
    }

    public static List<EventEnvelope> decode(ObjectMapper json, String payload) throws Exception {
        var root = json.readTree(payload);
        List<EventEnvelope> events;
        if (root.has("events")) {
            var batch = json.treeToValue(root, MessageBatch.class);
            if (batch.transactionId() == null || batch.events().isEmpty()) {
                throw new IllegalArgumentException("Invalid transaction batch");
            }
            events = batch.events();
        } else {
            events = List.of(json.treeToValue(root, EventEnvelope.class));
        }
        for (var e : events) {
            if (e.schemaVersion() != 1 || e.eventId() == null || e.aggregateId() == null || e.eventType() == null || e.data() == null) {
                throw new IllegalArgumentException("Invalid event envelope");
            }
        }
        return events;
    }
}
