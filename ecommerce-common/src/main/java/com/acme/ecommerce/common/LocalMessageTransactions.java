package com.acme.ecommerce.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.seata.core.context.RootContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class LocalMessageTransactions {
    private final TransactionMessageTransport transport;
    private final MessageTransactionStore store;
    private final LocalMessageExecutor executor;
    private final ObjectMapper json;
    private final LocalMessagePlanner planning;

    public LocalMessageTransactions(TransactionMessageTransport transport, MessageTransactionStore store,
                                    LocalMessageExecutor executor, ObjectMapper json, LocalMessagePlanner planning) {
        this.transport = transport;
        this.store = store;
        this.executor = executor;
        this.json = json;
        this.planning = planning;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void execute(Supplier<List<EventEnvelope>> planner, Runnable action) {
        if (RootContext.inGlobalTransaction()) {
            throw new IllegalStateException("Use Seata messaging inside a global transaction");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            List<EventEnvelope> plan = planning.plan(planner);
            if (plan.isEmpty()) {
                try {
                    executor.withoutMessage(action);
                    return;
                } catch (BusinessEvents.PlanChanged changed) {
                    continue;
                }
            }
            var batch = new MessageBatch(UUID.randomUUID().toString(), plan);
            String payload;
            try {
                payload = json.writeValueAsString(batch);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
            store.prepare(batch.transactionId(), payload);
            var work = new LocalMessageWork(batch, Hashing.sha256(payload), action);
            try {
                var state = transport.send(
                    MessageBuilder.withPayload(payload).setHeader("KEYS", plan.get(0).aggregateId())
                        .setHeader("transactionId", batch.transactionId()).build(), work);
                if (work.failure instanceof BusinessEvents.PlanChanged) {
                    continue;
                }
                if (work.completed || store.check(batch.transactionId(),
                    work.payloadHash) == RocketMQLocalTransactionState.COMMIT) {
                    return;
                }
                if (work.failure != null) {
                    throw work.failure;
                }
                throw new IllegalStateException("Local transaction not committed: " + state);
            } catch (RuntimeException ex) {
                if (store.check(batch.transactionId(), work.payloadHash) == RocketMQLocalTransactionState.COMMIT) {
                    return;
                }
                store.rollbackIfPrepared(batch.transactionId());
                throw ex;
            }
        }
        throw new IllegalStateException("Aggregate changed repeatedly; retry the business operation");
    }
}
