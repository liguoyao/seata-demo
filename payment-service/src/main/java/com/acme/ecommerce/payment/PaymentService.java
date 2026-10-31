package com.acme.ecommerce.payment;

import com.acme.ecommerce.common.*;
import com.acme.ecommerce.payment.mapper.ChannelEventMapper;
import com.acme.ecommerce.payment.mapper.ChannelOperationMapper;
import com.acme.ecommerce.payment.mapper.PaymentOrderMapper;
import com.acme.ecommerce.payment.mapper.RefundOrderMapper;
import com.acme.ecommerce.payment.model.ChannelEvent;
import com.acme.ecommerce.payment.model.ChannelOperation;
import com.acme.ecommerce.payment.model.PaymentOrder;
import com.acme.ecommerce.payment.model.RefundOrder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentService {
    private EventEnvelope planned(String type, PaymentOrder p) {
        return emitted.event(type, p.orderNo,
            Map.of("orderNo", p.orderNo, "paymentNo", p.paymentNo, "amount", p.amount.toPlainString(), "currency",
                p.currency));
    }

    private java.util.List<EventEnvelope> planConsume(EventEnvelope e) {
        if (inbox.wasDelivered("payment-service-events", e.eventId())) {
            return java.util.List.of();
        }
        var p = payments.selectById(e.aggregateId());
        if (p == null) {
            return java.util.List.of();
        }
        String type = null;
        switch (e.eventType()) {
            case Topics.PAYMENT_CREATE_REQUESTED -> {
                if ("SUCCESS".equals(p.status)) {
                    type = Topics.PAYMENT_SUCCEEDED;
                } else if (!Boolean.TRUE.equals(p.closeRequested) && "PAYING".equals(p.status)) {
                    type = Topics.PAYMENT_CREATED;
                }
            }
            case Topics.PAYMENT_CLOSE_REQUESTED -> {
                if ("SUCCESS".equals(p.status)) {
                    type = Topics.PAYMENT_SUCCEEDED;
                } else if ("CLOSED".equals(p.status)) {
                    type = Topics.PAYMENT_CLOSE_CONFIRMED;
                }
            }
            case Topics.REFUND_REQUESTED -> {
                var r = refunds.selectById("R" + p.orderNo);
                if (r != null && "SUCCEEDED".equals(r.status)) {
                    type = Topics.REFUND_SUCCEEDED;
                }
            }
        }
        return type == null ? java.util.List.of() : java.util.List.of(planned(type, p));
    }

    private java.util.List<EventEnvelope> planCallback(PaymentController.ChannelCallback c) {
        var p = payments.selectById(c.orderNo());
        return p == null || "SUCCESS".equals(p.status) || events.selectById(
            Hashing.sha256(c.channelCode() + "|" + c.merchantId() + "|" + c.channelEventId())) != null
            ? java.util.List.of() : java.util.List.of(planned(Topics.PAYMENT_SUCCEEDED, p));
    }

    private java.util.List<EventEnvelope> planFinish(ChannelOperation claimed, PaymentChannelGateway.QueryResult result,
                                                     PaymentChannelGateway.RefundResult refund, boolean created) {
        var op = operations.selectById(claimed.operationId);
        if (!owned(op, claimed)) {
            return java.util.List.of();
        }
        var p = payments.selectById(op.orderNo);
        String type = null;
        if ("CREATE".equals(op.type) && created) {
            if (!"SUCCESS".equals(p.status) && !"CLOSED".equals(p.status)) {
                type = Topics.PAYMENT_CREATED;
            }
        } else if ("REFUND".equals(
            op.type) && refund != null && refund.state() == PaymentChannelGateway.RefundState.SUCCEEDED) {
            type = Topics.REFUND_SUCCEEDED;
        } else if (result != null) {
            if (result.state() == PaymentChannelGateway.State.PAID) {
                if (!"SUCCESS".equals(p.status)) {
                    type = Topics.PAYMENT_SUCCEEDED;
                }
            } else if (!"SUCCESS".equals(p.status) && result.state() == PaymentChannelGateway.State.CLOSED) {
                type = Topics.PAYMENT_CLOSE_CONFIRMED;
            }
        }
        return type == null ? java.util.List.of() : java.util.List.of(planned(type, p));
    }

    public void finish(ChannelOperation claimed, PaymentChannelGateway.QueryResult result,
                       PaymentChannelGateway.RefundResult refund, boolean created) {
        transactions.execute(() -> planFinish(claimed, result, refund, created),
            () -> self.getObject().applyFinish(claimed, result, refund, created));
    }

    public void callback(PaymentController.ChannelCallback c) {
        transactions.execute(() -> planCallback(c), () -> self.getObject().applyCallback(c));
    }

    public void consume(EventEnvelope e) {
        transactions.execute(() -> planConsume(e), () -> self.getObject().applyConsume(e));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<PaymentService> self;

    @org.springframework.beans.factory.annotation.Autowired
    private LocalMessageTransactions transactions;

    private final PaymentOrderMapper payments;
    private final ChannelEventMapper events;
    private final RefundOrderMapper refunds;
    private final ChannelOperationMapper operations;
    private final Inbox inbox;
    private final BusinessEvents emitted;
    private final Clock clock;
    private final String channel, merchant;

    public PaymentService(PaymentOrderMapper payments, ChannelEventMapper events, RefundOrderMapper refunds,
                          ChannelOperationMapper operations, Inbox inbox, BusinessEvents emitted, Clock clock,
                          @Value("${payment.channel-code:DEFAULT}") String channel,
                          @Value("${payment.merchant-id:DEFAULT}") String merchant) {
        this.payments = payments;
        this.events = events;
        this.refunds = refunds;
        this.operations = operations;
        this.inbox = inbox;
        this.emitted = emitted;
        this.clock = clock;
        this.channel = channel;
        this.merchant = merchant;
    }

    private PaymentOrder lock(String no) {
        return payments.selectOne(new QueryWrapper<PaymentOrder>().eq("order_no", no).last("FOR UPDATE"));
    }

    @Transactional
    public void applyConsume(EventEnvelope e) {
        var p = lock(e.aggregateId());
        if (p == null) {
            p = new PaymentOrder();
            p.orderNo = e.aggregateId();
            p.paymentNo = (String)e.data().get("paymentNo");
            p.amount = new BigDecimal(e.data().get("amount").toString());
            p.currency = (String)e.data().get("currency");
            p.status = "INIT";
            p.channelCode = channel;
            p.merchantId = merchant;
            p.version = 0L;
            p.closeRequested = false;
            p.createdAt = clock.instant();
            p.updatedAt = p.createdAt;
            if (!("P" + p.orderNo).equals(p.paymentNo) || p.amount.signum() <= 0 || p.currency == null) {
                throw new IllegalArgumentException("Invalid payment intent");
            }
            payments.insert(p);
        }
        if (!p.paymentNo.equals(e.data().get("paymentNo")) || p.amount.compareTo(
            new BigDecimal(e.data().get("amount").toString())) != 0 || !p.currency.equals(e.data().get("currency"))) {
            throw new IllegalArgumentException("Conflicting payment intent");
        }
        if (!inbox.firstDelivery("payment-service-events", e)) {
            return;
        }
        switch (e.eventType()) {
            case Topics.PAYMENT_CREATE_REQUESTED -> {
                if ("SUCCESS".equals(p.status)) {
                    emit(Topics.PAYMENT_SUCCEEDED, p);
                } else if (!Boolean.TRUE.equals(p.closeRequested) && !"CLOSED".equals(p.status)) {
                    if ("PAYING".equals(p.status)) {
                        emit(Topics.PAYMENT_CREATED, p);
                    } else {
                        task("CREATE", p.orderNo);
                    }
                }
            }
            case Topics.PAYMENT_CLOSE_REQUESTED -> {
                p.closeRequested = true;
                if ("SUCCESS".equals(p.status)) {
                    emit(Topics.PAYMENT_SUCCEEDED, p);
                } else if ("CLOSED".equals(p.status)) {
                    emit(Topics.PAYMENT_CLOSE_CONFIRMED, p);
                } else {
                    p.status = "CLOSE_PENDING";
                    task("CLOSE", p.orderNo);
                }
            }
            case Topics.REFUND_REQUESTED -> {
                if (!"SUCCESS".equals(p.status)) {
                    throw new IllegalStateException("Refund requires confirmed funds");
                }
                String no = "R" + p.orderNo;
                var r = refunds.selectById(no);
                if (r == null) {
                    r = new RefundOrder();
                    r.refundNo = no;
                    r.paymentNo = p.paymentNo;
                    r.orderNo = p.orderNo;
                    r.amount = p.amount;
                    r.status = "REQUESTED";
                    r.createdAt = clock.instant();
                    r.updatedAt = r.createdAt;
                    refunds.insert(r);
                }
                if ("SUCCEEDED".equals(r.status)) {
                    emit(Topics.REFUND_SUCCEEDED, p);
                } else {
                    task("REFUND", p.orderNo);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported payment command");
        }
        p.updatedAt = clock.instant();
        if (payments.updateById(p) != 1) {
            throw new IllegalStateException("PAYMENT_VERSION_CONFLICT");
        }
        inbox.complete("payment-service-events", e.eventId());
    }

    private void task(String type, String order) {
        String id = type + ":" + order;
        var op = operations.selectById(id);
        if (op == null) {
            op = new ChannelOperation();
            op.operationId = id;
            op.orderNo = order;
            op.type = type;
            op.status = "NEW";
            op.attempts = 0;
            op.nextRetryTime = clock.instant();
            op.createdAt = clock.instant();
            op.updatedAt = op.createdAt;
            operations.insert(op);
        } else if ("DONE".equals(op.status)) {
            op.status = "RETRY";
            op.nextRetryTime = clock.instant();
            operations.updateById(op);
        }
    }

    @Transactional
    public void applyCallback(PaymentController.ChannelCallback c) {
        var p = lock(c.orderNo());
        if (p == null) {
            throw new IllegalArgumentException("Payment not found");
        }
        verify(p,
            new PaymentChannelGateway.QueryResult(PaymentChannelGateway.State.PAID, c.channelCode(), c.merchantId(),
                c.paymentNo(), c.channelTradeNo(), c.amount(), c.currency()));
        if (c.channelEventId() == null || c.channelEventId().length() > 100) {
            throw new IllegalArgumentException("Invalid channel event id");
        }
        String key = Hashing.sha256(c.channelCode() + "|" + c.merchantId() + "|" + c.channelEventId());
        String hash = Hashing.sha256(c.paymentNo() + "|" + c.orderNo() + "|" + c.amount().stripTrailingZeros()
            .toPlainString() + "|" + c.currency() + "|" + c.channelTradeNo());
        var prior = events.selectById(key);
        if (prior != null) {
            if (!hash.equals(prior.payloadHash)) {
                throw new IllegalStateException("CHANNEL_EVENT_CONFLICT");
            }
            return;
        }
        var event = new ChannelEvent();
        event.eventKey = key;
        event.paymentNo = c.paymentNo();
        event.payloadHash = hash;
        event.createdAt = clock.instant();
        events.insert(event);
        paid(p, c.channelTradeNo());
    }

    private void verify(PaymentOrder p, PaymentChannelGateway.QueryResult r) {
        if (r.state() != PaymentChannelGateway.State.PAID || !p.channelCode.equals(
            r.channelCode()) || !p.merchantId.equals(r.merchantId()) || !p.paymentNo.equals(
            r.paymentNo()) || r.amount() == null || p.amount.compareTo(r.amount()) != 0 || !p.currency.equals(
            r.currency()) || r.channelTradeNo() == null || r.channelTradeNo().isBlank()) {
            throw new IllegalArgumentException("PAYMENT_FACT_MISMATCH");
        }
    }

    private void paid(PaymentOrder p, String trade) {
        if ("SUCCESS".equals(p.status)) {
            if (!Objects.equals(p.channelTradeNo, trade)) {
                throw new IllegalStateException("MULTIPLE_CHANNEL_TRADES");
            }
            return;
        }
        p.status = "SUCCESS";
        p.channelTradeNo = trade;
        p.updatedAt = clock.instant();
        if (payments.updateById(p) != 1) {
            throw new IllegalStateException("PAYMENT_VERSION_CONFLICT");
        }
        emit(Topics.PAYMENT_SUCCEEDED, p);
    }

    @Transactional
    public ChannelOperation claim() {
        var now = clock.instant();
        var op = operations.selectOne(new QueryWrapper<ChannelOperation>().and(
            q -> q.in("status", "NEW", "RETRY", "UNKNOWN").le("next_retry_time", now).or().eq("status", "RUNNING")
                .lt("lease_until", now)).orderByAsc("next_retry_time").last("LIMIT 1 FOR UPDATE SKIP LOCKED"));
        if (op == null) {
            return null;
        }
        op.status = "RUNNING";
        op.attempts++;
        op.leaseToken = UUID.randomUUID().toString();
        op.leaseUntil = now.plusSeconds(60);
        op.updatedAt = now;
        operations.updateById(op);
        return op;
    }

    public PaymentOrder payment(String order) {
        return payments.selectById(order);
    }

    public boolean createOutstanding(String order) {
        var create = operations.selectById("CREATE:" + order);
        return create != null && !"DONE".equals(create.status);
    }

    public boolean neverCreated(String order) {
        return operations.selectById("CREATE:" + order) == null;
    }

    @Transactional
    public void applyFinish(ChannelOperation claimed, PaymentChannelGateway.QueryResult result,
                            PaymentChannelGateway.RefundResult refund, boolean created) {
        var op = operations.selectOne(
            new QueryWrapper<ChannelOperation>().eq("operation_id", claimed.operationId).last("FOR UPDATE"));
        if (!owned(op, claimed)) {
            return;
        }
        var p = lock(op.orderNo);
        boolean done = false;
        if ("CREATE".equals(op.type) && created) {
            if (!"SUCCESS".equals(p.status) && !"CLOSED".equals(p.status)) {
                if (!Boolean.TRUE.equals(p.closeRequested)) {
                    p.status = "PAYING";
                }
                emit(Topics.PAYMENT_CREATED, p);
                task("QUERY", p.orderNo);
            }
            done = true;
        } else if ("REFUND".equals(
            op.type) && refund != null && refund.state() == PaymentChannelGateway.RefundState.SUCCEEDED) {
            if (refund.channelRefundNo() == null || refund.channelRefundNo().isBlank()) {
                throw new IllegalArgumentException("Missing refund reference");
            }
            var r = refunds.selectById("R" + p.orderNo);
            if (!p.paymentNo.equals(refund.paymentNo()) || !r.refundNo.equals(
                refund.refundNo()) || refund.amount() == null || r.amount.compareTo(
                refund.amount()) != 0 || !p.currency.equals(refund.currency())) {
                throw new IllegalArgumentException("REFUND_FACT_MISMATCH");
            }
            r.status = "SUCCEEDED";
            r.channelRefundNo = refund.channelRefundNo();
            r.updatedAt = clock.instant();
            refunds.updateById(r);
            emit(Topics.REFUND_SUCCEEDED, p);
            done = true;
        } else if (result != null) {
            if (result.state() == PaymentChannelGateway.State.PAID) {
                verify(p, result);
                paid(p, result.channelTradeNo());
                done = true;
            } else if ("SUCCESS".equals(p.status)) {
                done = true;
            } else if (result.state() == PaymentChannelGateway.State.CLOSED) {
                if (!p.paymentNo.equals(result.paymentNo()) || !p.channelCode.equals(
                    result.channelCode()) || !p.merchantId.equals(result.merchantId())) {
                    throw new IllegalArgumentException("Close identity mismatch");
                }
                p.status = "CLOSED";
                p.closeRequested = true;
                emit(Topics.PAYMENT_CLOSE_CONFIRMED, p);
                done = true;
            }
        }
        p.updatedAt = clock.instant();
        if (payments.updateById(p) != 1) {
            throw new IllegalStateException("PAYMENT_VERSION_CONFLICT");
        }
        op.status = done ? "DONE" : "UNKNOWN";
        op.nextRetryTime = clock.instant().plusSeconds(30);
        op.updatedAt = clock.instant();
        operations.updateById(op);
    }

    @Transactional
    public void failed(ChannelOperation claimed, Exception ex) {
        var op = operations.selectOne(
            new QueryWrapper<ChannelOperation>().eq("operation_id", claimed.operationId).last("FOR UPDATE"));
        if (!owned(op, claimed)) {
            return;
        }
        op.status = "UNKNOWN";
        op.lastError = ex.toString().substring(0, Math.min(1000, ex.toString().length()));
        op.nextRetryTime = clock.instant().plusSeconds(Math.min(300, 1L << Math.min(op.attempts, 8)));
        op.updatedAt = clock.instant();
        operations.updateById(op);
    }

    private boolean owned(ChannelOperation current, ChannelOperation claimed) {
        return current != null && "RUNNING".equals(current.status) && Objects.equals(current.leaseToken,
            claimed.leaseToken);
    }

    private void emit(String type, PaymentOrder p) {
        emitted.append(type, p.orderNo,
            Map.of("orderNo", p.orderNo, "paymentNo", p.paymentNo, "amount", p.amount.toPlainString(), "currency",
                p.currency));
    }
}
