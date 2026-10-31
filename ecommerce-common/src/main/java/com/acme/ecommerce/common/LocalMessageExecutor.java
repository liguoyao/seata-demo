package com.acme.ecommerce.common;

import com.acme.ecommerce.common.mapper.MessageTransactionMapper;
import com.acme.ecommerce.common.model.MessageTransaction;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class LocalMessageExecutor {
    private final MessageTransactionMapper rows;
    private final BusinessEvents events;
    private final Clock clock;

    public LocalMessageExecutor(MessageTransactionMapper rows, BusinessEvents events, Clock clock) {
        this.rows = rows;
        this.events = events;
        this.clock = clock;
    }

    @org.apache.seata.spring.annotation.GlobalLock
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15, rollbackFor = Exception.class)
    public void execute(LocalMessageWork work) {
        var r = rows.selectOne(
            new QueryWrapper<MessageTransaction>().eq("transaction_id", work.batch.transactionId()).last("FOR UPDATE"));
        if (r == null || !r.payloadHash.equals(work.payloadHash)) {
            throw new IllegalArgumentException("Unknown message transaction");
        }
        if ("COMMITTED".equals(r.status)) {
            return;
        }
        if (!"PREPARED".equals(r.status) || !clock.instant().isBefore(r.deadline)) {
            throw new IllegalStateException("Message transaction expired or rolled back");
        }
        events.execute(work.batch.events(), work.action);
        r.status = "COMMITTED";
        r.completedAt = clock.instant();
        rows.updateById(r);
    }

    @org.apache.seata.spring.annotation.GlobalLock
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15, rollbackFor = Exception.class)
    public void withoutMessage(Runnable action) {
        events.execute(List.of(), action);
    }
}
