package com.acme.ecommerce.common;

import com.acme.ecommerce.common.mapper.InboxEventMapper;
import com.acme.ecommerce.common.model.InboxEvent;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Inbox {
  private final InboxEventMapper events;
  private final Clock clock;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public Inbox(
      InboxEventMapper events, Clock clock, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.events = events;
    this.clock = clock;
    this.json = json;
  }

  public boolean wasDelivered(String group, String id) {
    return events.selectCount(
            new QueryWrapper<InboxEvent>().eq("consumer_name", group).eq("event_id", id))
        > 0;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean firstDelivery(String group, EventEnvelope event) {
    String id = event.eventId(), type = event.eventType(), hash;
    try {
      hash =
          Hashing.sha256(
              type
                  + "|"
                  + event.aggregateId()
                  + "|"
                  + event.schemaVersion()
                  + "|"
                  + json.writeValueAsString(new java.util.TreeMap<>(event.data())));
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
    var previous =
        events.selectOne(
            new QueryWrapper<InboxEvent>().eq("consumer_name", group).eq("event_id", id));
    if (previous != null) {
      if (!hash.equals(previous.payloadHash))
        throw new IllegalArgumentException("EVENT_ID_PAYLOAD_CONFLICT");
      return false;
    }
    var e = new InboxEvent();
    e.consumerName = group;
    e.eventId = id;
    e.eventType = type;
    e.aggregateId = event.aggregateId();
    e.payloadHash = hash;
    e.status = "PROCESSING";
    e.receivedAt = clock.instant();
    return events.insert(e) == 1;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(String group, String id) {
    events.update(
        null,
        new UpdateWrapper<InboxEvent>()
            .eq("consumer_name", group)
            .eq("event_id", id)
            .set("status", "DONE")
            .set("consumed_at", clock.instant()));
  }
}
