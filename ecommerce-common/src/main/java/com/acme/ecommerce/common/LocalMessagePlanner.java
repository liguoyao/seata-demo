package com.acme.ecommerce.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

@Service
public class LocalMessagePlanner {
    // A fresh read transaction on every attempt prevents MyBatis first-level cache from
    // retaining the pre-race aggregate state across retries.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<EventEnvelope> plan(Supplier<List<EventEnvelope>> planner) {
        return planner.get();
    }
}
