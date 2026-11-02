package com.acme.ecommerce.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RocketMQTransactionListener
public class LocalTransactionListener implements RocketMQLocalTransactionListener {
    private static final Logger log = LoggerFactory.getLogger(LocalTransactionListener.class);
    private final LocalMessageExecutor executor;
    private final MessageTransactionStore store;
    private final ObjectMapper json;

    public LocalTransactionListener(LocalMessageExecutor executor, MessageTransactionStore store, ObjectMapper json) {
        this.executor = executor;
        this.store = store;
        this.json = json;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object argument) {
        if (!(argument instanceof LocalMessageWork work)) {
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        try {
            String payload = body(message);
            var batch = json.readValue(payload, MessageBatch.class);
            if (!batch.transactionId().equals(work.batch.transactionId()) || !Hashing.sha256(payload)
                .equals(work.payloadHash)) {
                throw new IllegalArgumentException("Message/transaction mismatch");
            }
            executor.execute(work);
            work.completed = true;
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception ex) {
            work.failure = ex instanceof RuntimeException r ? r : new IllegalStateException(ex);
            try {
                store.rollbackIfPrepared(work.batch.transactionId());
            } catch (Exception rollback) {
                log.warn("Cannot record rollback; broker will check", rollback);
            }
            return check(message);
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        return check(message);
    }

    private RocketMQLocalTransactionState check(Message message) {
        try {
            String payload = body(message);
            var batch = json.readValue(payload, MessageBatch.class);
            return store.check(batch.transactionId(), Hashing.sha256(payload));
        } catch (Exception ex) {
            log.warn("Transaction check is unresolved", ex);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private String body(Message<?> message) {
        Object payload = message.getPayload();
        return payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : payload.toString();
    }
}
