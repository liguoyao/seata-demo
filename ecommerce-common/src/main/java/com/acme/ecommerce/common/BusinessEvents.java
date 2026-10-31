package com.acme.ecommerce.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Collects the exact events declared in a half-message; never publishes or scans a database. */
@Component
public class BusinessEvents {
  private final Clock clock;
  private final ObjectMapper json;
  private final ThreadLocal<Deque<EventEnvelope>> expected = new ThreadLocal<>();

  public BusinessEvents(Clock clock, ObjectMapper json) {
    this.clock = clock;
    this.json = json;
  }

  public EventEnvelope event(String type, String aggregate, Map<String, Object> data) {
    return new EventEnvelope(
        UUID.randomUUID().toString(), type, aggregate, 1, clock.instant(), data);
  }

  public String append(String type, String aggregate, Map<String, Object> data) {
    var queue = expected.get();
    if (queue == null)
      throw new IllegalStateException("Event emitted outside a transactional message boundary");
    var event = queue.poll();
    if (event == null
        || !event.eventType().equals(type)
        || !event.aggregateId().equals(aggregate)
        || !canonical(event.data()).equals(canonical(data))) throw new PlanChanged();
    return event.eventId();
  }

  public void execute(List<EventEnvelope> plan, Runnable work) {
    if (expected.get() != null) throw new IllegalStateException("Nested message transaction");
    expected.set(new ArrayDeque<>(plan));
    try {
      work.run();
      if (!expected.get().isEmpty()) throw new PlanChanged();
    } finally {
      expected.remove();
    }
  }

  private String canonical(Map<String, Object> data) {
    try {
      return json.writeValueAsString(new TreeMap<>(data));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static class PlanChanged extends RuntimeException {
    public PlanChanged() {
      super("Aggregate changed since event planning; retry before committing");
    }
  }
}
