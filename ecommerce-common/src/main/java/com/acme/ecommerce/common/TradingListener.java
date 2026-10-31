package com.acme.ecommerce.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public abstract class TradingListener implements RocketMQListener<MessageExt> {
    private static final Logger log = LoggerFactory.getLogger(TradingListener.class);
    private final ObjectMapper json;
    private final MessageFailureStore failures;
    private final String group;

    protected TradingListener(ObjectMapper json, MessageFailureStore failures, String group) {
        this.json = json;
        this.failures = failures;
        this.group = group;
    }

    protected abstract void consume(EventEnvelope event);

    protected abstract java.util.Set<String> supportedTypes();

    public void processPayload(String payload) throws Exception {
        for (var e : EventMessages.decode(json, payload)) {
            if (supportedTypes().contains(e.eventType())) {
                consume(e);
            }
        }
    }

    @Override
    public void onMessage(MessageExt message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String id = message.getMsgId();
        try {
            processPayload(payload);
        } catch (Exception ex) {
            log.error("Consumer {} event {} attempt {} failed", group, id, message.getReconsumeTimes(), ex);
            try {
                failures.record(group, id, payload, message.getReconsumeTimes() + 1, ex);
            } catch (Exception storeError) {
                log.error("Failure audit unavailable", storeError);
            }
            throw new IllegalStateException("Consumption failed", ex);
        }
    }
}
