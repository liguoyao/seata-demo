package com.acme.ecommerce.common;

import com.acme.ecommerce.common.mapper.MessageFailureMapper;
import com.acme.ecommerce.common.model.MessageFailure;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MessageFailureStore {
  private final MessageFailureMapper failures;
  private final Clock clock;

  public MessageFailureStore(MessageFailureMapper failures, Clock clock) {
    this.failures = failures;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String group, String id, String payload, int attempts, Exception ex) {
    var f = new MessageFailure();
    f.consumerGroup = group;
    f.eventId = id;
    f.payload = payload;
    f.attempts = attempts;
    f.lastError = ex.toString().substring(0, Math.min(1000, ex.toString().length()));
    f.updatedAt = clock.instant();
    failures.insert(f);
  }
}
