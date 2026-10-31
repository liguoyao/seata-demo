package com.acme.ecommerce.common;

import java.util.List;

public record MessageBatch(String transactionId, List<EventEnvelope> events) {
  public MessageBatch {
    events = List.copyOf(events);
  }
}
