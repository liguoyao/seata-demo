package com.acme.ecommerce.order;

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
@RocketMQMessageListener(topic = "${messaging.topic:ECOMMERCE_TRADING_TX}", consumerGroup = "order-service-events",
    selectorExpression = "LOCAL_TX || PAYMENT_CREATED || PAYMENT_SUCCEEDED || PAYMENT_CLOSE_CONFIRMED || REFUND_SUCCEEDED ||" + " STOCK_CONFIRMED || STOCK_RELEASED || STOCK_CONFIRM_FAILED",
    maxReconsumeTimes = 16)
public class OrderEventConsumer extends TradingListener {
    private final OrderFlow service;

    public OrderEventConsumer(ObjectMapper json, MessageFailureStore failures, OrderFlow service) {
        super(json, failures, "order-service-events");
        this.service = service;
    }

    @Override
    protected java.util.Set<String> supportedTypes() {
        return java.util.Set.of(Topics.PAYMENT_CREATED, Topics.PAYMENT_SUCCEEDED, Topics.PAYMENT_CLOSE_CONFIRMED,
            Topics.REFUND_SUCCEEDED, Topics.STOCK_CONFIRMED, Topics.STOCK_RELEASED, Topics.STOCK_CONFIRM_FAILED);
    }

    @Override
    protected void consume(EventEnvelope e) {
        service.consume(e);
    }
}
