package com.acme.ecommerce.payment;

import com.acme.ecommerce.common.EventEnvelope;
import com.acme.ecommerce.common.MessageFailureStore;
import com.acme.ecommerce.common.Topics;
import com.acme.ecommerce.common.TradingListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"messaging.enabled", "messaging.consumers-enabled"}, havingValue = "true",
    matchIfMissing = true)
@RocketMQMessageListener(topic = "${messaging.topic:ECOMMERCE_TRADING_TX}", consumerGroup = "payment-service-events",
    selectorExpression = "LOCAL_TX || PAYMENT_CREATE_REQUESTED || PAYMENT_CLOSE_REQUESTED || REFUND_REQUESTED",
    maxReconsumeTimes = 16)
public class PaymentEventConsumer extends TradingListener {
    private final PaymentService service;

    public PaymentEventConsumer(ObjectMapper json, MessageFailureStore failures, PaymentService service) {
        super(json, failures, "payment-service-events");
        this.service = service;
    }

    @Override
    protected java.util.Set<String> supportedTypes() {
        return java.util.Set.of(Topics.PAYMENT_CREATE_REQUESTED, Topics.PAYMENT_CLOSE_REQUESTED,
            Topics.REFUND_REQUESTED);
    }

    @Override
    protected void consume(EventEnvelope e) {
        service.consume(e);
    }
}
