package com.acme.ecommerce.payment;

import com.acme.ecommerce.payment.model.ChannelOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workers.enabled", havingValue = "true", matchIfMissing = true)
public class ChannelWorker {
    private static final Logger log = LoggerFactory.getLogger(ChannelWorker.class);
    @org.springframework.beans.factory.annotation.Autowired private ChannelIsolation isolation;
    private final PaymentService service;
    private final PaymentChannelGateway gateway;

    public ChannelWorker(PaymentService service, PaymentChannelGateway gateway) {
        this.service = service;
        this.gateway = gateway;
    }

    @org.springframework.beans.factory.annotation.Value("${payment.worker-concurrency:4}")
    private int concurrency;
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    @Scheduled(fixedDelayString = "${payment.worker-ms:1000}")
    public void run() {
        for (int i=0; i<Math.max(1,concurrency) && active.get()<Math.max(1,concurrency); i++) {
            active.incrementAndGet();
            try {
                executor.submit(() -> {
                    try { var op=service.claim(); if(op!=null) execute(op); }
                    catch(Exception ex) { log.warn("Channel claim failed",ex); }
                    finally { active.decrementAndGet(); }
                });
            } catch(java.util.concurrent.RejectedExecutionException ex) { active.decrementAndGet(); return; }
        }
    }

    public void execute(ChannelOperation op) {
        try {
            var p = service.payment(op.orderNo);
            PaymentChannelGateway.QueryResult result = null;
            PaymentChannelGateway.RefundResult refund = null;
            boolean created = false;
            switch (op.type) {
                case "CREATE" -> created = isolation.call(() -> gateway.create(p.paymentNo, p.amount, p.currency));
                case "QUERY" -> result = isolation.call(() -> gateway.query(p.paymentNo));
                case "CLOSE" -> {
                    if (service.createOutstanding(op.orderNo)) {
                        throw new IllegalStateException("Create is unresolved; close must wait");
                    }
                    result = service.neverCreated(op.orderNo) ? new PaymentChannelGateway.QueryResult(
                        PaymentChannelGateway.State.CLOSED, p.channelCode, p.merchantId, p.paymentNo, null, p.amount,
                        p.currency) : isolation.call(() -> gateway.close(p.paymentNo));
                }
                case "REFUND" -> {
                    String no = "R" + p.orderNo;
                    refund = isolation.call(() -> gateway.queryRefund(p.paymentNo, no));
                    if (refund.state() == PaymentChannelGateway.RefundState.NOT_FOUND) {
                        refund = isolation.call(() -> gateway.refund(p.paymentNo, no, p.amount));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown operation");
            }
            service.finish(op, result, refund, created);
            if (op.attempts >= 100) {
                log.error("Channel operation needs attention: {}", op.operationId);
            }
        } catch (Exception ex) {
            service.failed(op, ex);
            log.warn("Channel operation {} unresolved", op.operationId, ex);
        }
    }
}
