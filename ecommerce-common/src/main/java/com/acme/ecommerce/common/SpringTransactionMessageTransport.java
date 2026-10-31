package com.acme.ecommerce.common;

import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "messaging.enabled", havingValue = "true", matchIfMissing = true)
public class SpringTransactionMessageTransport implements TransactionMessageTransport {
    private final RocketMQTemplate template;
    private final String topic;

    public SpringTransactionMessageTransport(RocketMQTemplate template,
                                             @Value("${messaging.topic:ECOMMERCE_TRADING_TX}") String topic) {
        this.template = template;
        this.topic = topic;
    }

    @Override
    public RocketMQLocalTransactionState send(Message<String> message, LocalMessageWork work) {
        var result = template.sendMessageInTransaction(topic + ":LOCAL_TX", message, work);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Half-message not acknowledged by broker");
        }
        return switch (result.getLocalTransactionState()) {
            case COMMIT_MESSAGE -> RocketMQLocalTransactionState.COMMIT;
            case ROLLBACK_MESSAGE -> RocketMQLocalTransactionState.ROLLBACK;
            default -> RocketMQLocalTransactionState.UNKNOWN;
        };
    }
}
