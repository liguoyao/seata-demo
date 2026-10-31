package com.acme.ecommerce.common;

import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dlq.capture-group", havingValue = "stock-service-events")
@RocketMQMessageListener(
    topic = "%DLQ%stock-service-events",
    consumerGroup = "stock-service-dlq-audit",
    maxReconsumeTimes = 16)
public class StockDeadLetterListener implements RocketMQListener<MessageExt> {
  private final MessageFailureStore failures;

  public StockDeadLetterListener(MessageFailureStore failures) {
    this.failures = failures;
  }

  public void onMessage(MessageExt msg) {
    failures.record(
        "%DLQ%stock-service-events",
        msg.getMsgId(),
        new String(msg.getBody(), StandardCharsets.UTF_8),
        msg.getReconsumeTimes(),
        new IllegalStateException("Dead letter requires reviewed replay"));
  }
}
