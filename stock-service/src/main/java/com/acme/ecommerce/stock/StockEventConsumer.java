package com.acme.ecommerce.stock;

import com.acme.ecommerce.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = {"messaging.enabled", "messaging.consumers-enabled"},
    havingValue = "true",
    matchIfMissing = true)
@RocketMQMessageListener(
    topic = "${messaging.topic:ECOMMERCE_TRADING_TX}",
    consumerGroup = "stock-service-events",
    selectorExpression = "LOCAL_TX || STOCK_CONFIRM_REQUESTED || STOCK_RELEASE_REQUESTED",
    maxReconsumeTimes = 16)
public class StockEventConsumer extends TradingListener {
  private final StockService service;

  public StockEventConsumer(ObjectMapper json, MessageFailureStore failures, StockService service) {
    super(json, failures, "stock-service-events");
    this.service = service;
  }

  protected java.util.Set<String> supportedTypes() {
    return java.util.Set.of(Topics.STOCK_CONFIRM_REQUESTED, Topics.STOCK_RELEASE_REQUESTED);
  }

  protected void consume(EventEnvelope e) {
    service.consume(e);
  }
}
