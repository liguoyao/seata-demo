package com.acme.ecommerce.common;

import com.acme.ecommerce.common.mapper.MessageTransactionMapper;
import com.acme.ecommerce.common.model.MessageTransaction;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class MessageTransactionStore {
    private final MessageTransactionMapper rows;
    private final Clock clock;

    public MessageTransactionStore(MessageTransactionMapper rows, Clock clock) {
        this.rows = rows;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(String id, String payload) {
        var r = new MessageTransaction();
        r.transactionId = id;
        r.payload = payload;
        r.payloadHash = Hashing.sha256(payload);
        r.status = "PREPARED";
        r.createdAt = clock.instant();
        r.deadline = r.createdAt.plusSeconds(120);
        rows.insert(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rollbackIfPrepared(String id) {
        var r = lock(id);
        if (r != null && "PREPARED".equals(r.status)) {
            r.status = "ROLLED_BACK";
            r.completedAt = clock.instant();
            rows.updateById(r);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 2)
    public RocketMQLocalTransactionState check(String id, String hash) {
        var r = rows.selectById(id);
        if (r == null) {
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        if (!r.payloadHash.equals(hash)) {
            throw new IllegalArgumentException("Transaction payload conflict");
        }
        if ("COMMITTED".equals(r.status)) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        if ("ROLLED_BACK".equals(r.status)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        if (clock.instant().isBefore(r.deadline)) {
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        // An active local transaction holds this row. NOWAIT prevents expiring its in-flight work.
        r = rows.selectOne(new QueryWrapper<MessageTransaction>().eq("transaction_id", id).last("FOR UPDATE NOWAIT"));
        if ("COMMITTED".equals(r.status)) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        if ("ROLLED_BACK".equals(r.status)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        r.status = "ROLLED_BACK";
        r.completedAt = clock.instant();
        rows.updateById(r);
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    private MessageTransaction lock(String id) {
        return rows.selectOne(new QueryWrapper<MessageTransaction>().eq("transaction_id", id).last("FOR UPDATE"));
    }
}
