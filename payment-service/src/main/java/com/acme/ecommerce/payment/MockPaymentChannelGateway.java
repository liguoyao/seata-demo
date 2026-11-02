package com.acme.ecommerce.payment;

import com.acme.ecommerce.payment.mapper.PaymentOrderMapper;
import com.acme.ecommerce.payment.model.PaymentOrder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mock 渠道网关：模拟支付渠道，全部操作直接返回成功，不抛异常。
 *
 * <p>默认（未启用 payment-channel profile）时生效，用于本地联调走通
 * CREATE -> QUERY(PAID) -> PAYMENT_SUCCEEDED -> CLOSE / REFUND 全流程。
 */
@Component
@Profile("!payment-channel")
public class MockPaymentChannelGateway implements PaymentChannelGateway {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentChannelGateway.class);

    private final PaymentOrderMapper payments;

    public MockPaymentChannelGateway(PaymentOrderMapper payments) {
        this.payments = payments;
    }

    private PaymentOrder byPaymentNo(String paymentNo) {
        return payments.selectOne(new QueryWrapper<PaymentOrder>().eq("payment_no", paymentNo));
    }

    @Override
    public boolean verify(PaymentController.ChannelCallback callback) {
        return true;
    }

    @Override
    public boolean create(String paymentNo, BigDecimal amount, String currency) {
        log.info("[MOCK] channel create: paymentNo={} amount={} {}", paymentNo, amount, currency);
        return true;
    }

    @Override
    public QueryResult query(String paymentNo) {
        var p = byPaymentNo(paymentNo);
        if (p == null) {
            log.warn("[MOCK] channel query: paymentNo={} not found", paymentNo);
            return new QueryResult(State.NOT_FOUND, "MOCK", "MOCK", paymentNo, null, null, null);
        }
        if (Instant.now().isBefore(p.createdAt.plusSeconds(5))) {
            return new QueryResult(State.UNPAID, p.channelCode, p.merchantId, p.paymentNo, null, p.amount, p.currency);
        }
        log.info("[MOCK] channel query: paymentNo={} -> PAID", paymentNo);
        return new QueryResult(State.PAID, p.channelCode, p.merchantId, p.paymentNo, "MOCK" + paymentNo, p.amount,
            p.currency);
    }

    @Override
    public QueryResult close(String paymentNo) {
        var p = byPaymentNo(paymentNo);
        if (p == null) {
            log.warn("[MOCK] channel close: paymentNo={} not found", paymentNo);
            return new QueryResult(State.NOT_FOUND, "MOCK", "MOCK", paymentNo, null, null, null);
        }
        log.info("[MOCK] channel close: paymentNo={} -> CLOSED", paymentNo);
        return new QueryResult(State.CLOSED, p.channelCode, p.merchantId, p.paymentNo, null, p.amount, p.currency);
    }

    @Override
    public RefundResult refund(String paymentNo, String refundNo, BigDecimal amount) {
        var p = byPaymentNo(paymentNo);
        if (p == null) {
            log.warn("[MOCK] channel refund: paymentNo={} not found", paymentNo);
            return new RefundResult(RefundState.NOT_FOUND, null, paymentNo, refundNo, amount, null);
        }
        log.info("[MOCK] channel refund: paymentNo={} refundNo={} -> SUCCEEDED", paymentNo, refundNo);
        return new RefundResult(RefundState.SUCCEEDED, "MOCKR" + refundNo, paymentNo, refundNo, amount, p.currency);
    }

    @Override
    public RefundResult queryRefund(String paymentNo, String refundNo) {
        // 返回 NOT_FOUND，让 ChannelWorker 走 gateway.refund() 触发模拟退款
        return new RefundResult(RefundState.NOT_FOUND, null, paymentNo, refundNo, null, null);
    }
}
