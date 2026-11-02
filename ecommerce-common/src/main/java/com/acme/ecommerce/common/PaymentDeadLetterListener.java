package com.acme.ecommerce.common;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "dlq.capture-group", havingValue = "payment-service-events")
@RocketMQMessageListener(topic = "%DLQ%payment-service-events", consumerGroup = "payment-service-dlq-audit",
    maxReconsumeTimes = 16)
public class PaymentDeadLetterListener implements RocketMQListener<MessageExt> {
    private final MessageFailureStore failures;

    public PaymentDeadLetterListener(MessageFailureStore failures) {
        this.failures = failures;
    }

    @Override
    public void onMessage(MessageExt msg) {
        failures.record("%DLQ%payment-service-events", msg.getMsgId(),
            new String(msg.getBody(), StandardCharsets.UTF_8), msg.getReconsumeTimes(),
            new IllegalStateException("Dead letter requires reviewed replay"));
    }
}
