package com.acme.ecommerce.common;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(
    String eventId,
    String eventType,
    String aggregateId,
    int schemaVersion,
    Instant occurredAt,
    Map<String, Object> data) {}
