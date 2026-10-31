package com.acme.ecommerce.order;

import com.acme.ecommerce.common.BusinessEvents;
import com.acme.ecommerce.common.Topics;
import com.acme.ecommerce.order.model.TradeOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.integration.rocketmq.SeataMQProducer;
import org.apache.seata.integration.rocketmq.SeataMQProducerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true", matchIfMissing = true)
public class SeataCheckoutMessagePublisher implements CheckoutMessagePublisher {
    private final SeataMQProducer producer;
    private final BusinessEvents events;
    private final ObjectMapper json;
    private final String topic;

    public SeataCheckoutMessagePublisher(BusinessEvents events, ObjectMapper json,
                                         @Value("${messaging.topic:ECOMMERCE_TRADING_TX}") String topic,
                                         @Value("${rocketmq.name-server}") String nameserver,
                                         @Value("${spring.application.name}") String app) throws Exception {
        // Do not expose this as a DefaultMQProducer bean: doing so would suppress the
        // RocketMQ Spring starter's separate local-transaction producer.
        RocketMessageJsonCompatibility.register();
        this.producer = SeataMQProducerFactory.createSingle(nameserver, app + "-seata-tx-producer");
        this.events = events;
        this.json = json;
        this.topic = topic;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        producer.shutdown();
    }

    @Override
    public void publish(TradeOrder order) {
        if (!RootContext.inGlobalTransaction()) {
            throw new IllegalStateException("Seata MQ must not degrade to a normal send without XID");
        }
        try {
            var event = events.event(Topics.PAYMENT_CREATE_REQUESTED, order.orderNo,
                Map.of("orderNo", order.orderNo, "paymentNo", "P" + order.orderNo, "amount",
                    order.amount.toPlainString(), "currency", order.currency));
            var message =
                new Message(topic, Topics.PAYMENT_CREATE_REQUESTED, order.orderNo, json.writeValueAsBytes(event));
            message.putUserProperty("eventId", event.eventId());
            var result = producer.send(message, 3000);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Seata half-message not acknowledged");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Seata send interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot enlist payment command in Seata transaction", ex);
        }
    }
}
