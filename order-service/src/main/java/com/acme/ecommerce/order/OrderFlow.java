package com.acme.ecommerce.order;

import com.acme.ecommerce.common.*;
import com.acme.ecommerce.order.mapper.OrderSagaMapper;
import com.acme.ecommerce.order.mapper.TradeOrderMapper;
import com.acme.ecommerce.order.model.OrderSaga;
import com.acme.ecommerce.order.model.TradeOrder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

@Service
public class OrderFlow {
    private EventEnvelope planned(String type, TradeOrder o) {
        return emitted.event(type, o.orderNo,
            Map.of("orderNo", o.orderNo, "paymentNo", "P" + o.orderNo, "amount", o.amount.toPlainString(), "currency",
                o.currency));
    }

    private java.util.List<EventEnvelope> planCancel(String no) {
        var o = orders.selectById(no);
        if (o != null && Set.of("PAYMENT_PREPARING", "WAITING_PAYMENT").contains(o.status) && !clock.instant()
            .isBefore(o.paymentDeadline)) {
            return java.util.List.of(planned(Topics.PAYMENT_CLOSE_REQUESTED, o));
        }
        return java.util.List.of();
    }

    private java.util.List<EventEnvelope> planConsume(EventEnvelope e) {
        if (inbox.wasDelivered("order-service-events", e.eventId())) {
            return java.util.List.of();
        }
        var o = orders.selectById(e.aggregateId());
        if (o == null) {
            return java.util.List.of();
        }
        String type = null;
        if (Topics.PAYMENT_SUCCEEDED.equals(e.eventType())) {
            if (Set.of("PAYMENT_PREPARING", "WAITING_PAYMENT", "CANCEL_REQUESTED").contains(o.status)) {
                type = Topics.STOCK_CONFIRM_REQUESTED;
            } else if (Set.of("CANCEL_PENDING_STOCK_RELEASE", "CANCELLED").contains(o.status)) {
                type = Topics.REFUND_REQUESTED;
            }
        }
        if (Topics.PAYMENT_CLOSE_CONFIRMED.equals(e.eventType()) && "CANCEL_REQUESTED".equals(o.status)) {
            type = Topics.STOCK_RELEASE_REQUESTED;
        }
        if (Topics.STOCK_CONFIRM_FAILED.equals(e.eventType()) && "PAID_PENDING_STOCK_CONFIRM".equals(o.status)) {
            type = Topics.REFUND_REQUESTED;
        }
        return type == null ? java.util.List.of() : java.util.List.of(planned(type, o));
    }

    private java.util.List<EventEnvelope> planRecover(String no) {
        var o = orders.selectById(no);
        var s = sagas.selectById(no);
        if (o == null || s == null || s.nextRetryTime.isAfter(clock.instant()) || Set.of("COMPLETED", "CANCELLED",
            "REFUNDED").contains(o.status)) {
            return java.util.List.of();
        }
        var result = new java.util.ArrayList<EventEnvelope>();
        switch (o.status) {
            case "PAYMENT_PREPARING" -> result.add(planned(Topics.PAYMENT_CREATE_REQUESTED, o));
            case "WAITING_PAYMENT" -> {
                if (!clock.instant().isBefore(o.paymentDeadline)) {
                    result.add(planned(Topics.PAYMENT_CLOSE_REQUESTED, o));
                }
            }
            case "CANCEL_REQUESTED" -> result.add(planned(Topics.PAYMENT_CLOSE_REQUESTED, o));
            case "PAID_PENDING_STOCK_CONFIRM" -> result.add(planned(Topics.STOCK_CONFIRM_REQUESTED, o));
            case "CANCEL_PENDING_STOCK_RELEASE" -> result.add(planned(Topics.STOCK_RELEASE_REQUESTED, o));
            case "REFUND_REQUIRED" -> {
                if (!Boolean.TRUE.equals(s.refundCompleted)) {
                    result.add(planned(Topics.REFUND_REQUESTED, o));
                }
                if (!Boolean.TRUE.equals(s.stockReleased)) {
                    result.add(planned(Topics.STOCK_RELEASE_REQUESTED, o));
                }
            }
        }
        return result;
    }

    public void recover(String no) {
        transactions.execute(() -> planRecover(no), () -> self.getObject().applyRecover(no));
    }

    public void consume(EventEnvelope e) {
        transactions.execute(() -> planConsume(e), () -> self.getObject().applyConsume(e));
    }

    public void cancel(String no) {
        transactions.execute(() -> planCancel(no), () -> self.getObject().applyCancel(no));
    }

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<OrderFlow> self;

    @Autowired
    private LocalMessageTransactions transactions;

    private final TradeOrderMapper orders;
    private final OrderSagaMapper sagas;
    private final Inbox inbox;
    private final BusinessEvents emitted;
    private final Clock clock;

    public OrderFlow(TradeOrderMapper orders, OrderSagaMapper sagas, Inbox inbox, BusinessEvents emitted, Clock clock) {
        this.orders = orders;
        this.sagas = sagas;
        this.inbox = inbox;
        this.emitted = emitted;
        this.clock = clock;
    }

    private TradeOrder lock(String no) {
        var o = orders.selectOne(new QueryWrapper<TradeOrder>().eq("order_no", no).last("FOR UPDATE"));
        if (o == null) {
            throw new IllegalArgumentException("Order not found");
        }
        return o;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void initialize(TradeOrder o) {
        var s = new OrderSaga();
        s.orderNo = o.orderNo;
        s.currentStep = o.status;
        s.status = "RUNNING";
        s.retryCount = 0;
        s.nextRetryTime = clock.instant().plusSeconds(30);
        s.createdAt = clock.instant();
        s.updatedAt = s.createdAt;
        s.stockReleased = false;
        s.refundCompleted = false;
        sagas.insert(s);
    }

    @GlobalLock
    @Transactional
    public void applyCancel(String no) {
        var o = lock(no);
        if (("PAYMENT_PREPARING".equals(o.status) || "WAITING_PAYMENT".equals(o.status)) && !clock.instant()
            .isBefore(o.paymentDeadline)) {
            o.status = "CANCEL_REQUESTED";
            save(o, sagas.selectById(no));
            emit(Topics.PAYMENT_CLOSE_REQUESTED, o);
        }
    }

    @GlobalLock
    @Transactional
    public void applyConsume(EventEnvelope e) {
        var o = lock(e.aggregateId());
        if ("AT_PENDING".equals(o.status)) {
            throw new IllegalStateException("Resolve legacy AT_PENDING orders before migrating");
        }
        if (!inbox.firstDelivery("order-service-events", e)) {
            return;
        }
        var s = sagas.selectById(o.orderNo);
        switch (e.eventType()) {
            case Topics.PAYMENT_CREATED -> {
                if ("PAYMENT_PREPARING".equals(o.status)) {
                    o.status = "WAITING_PAYMENT";
                }
            }
            case Topics.PAYMENT_SUCCEEDED -> {
                if (!("P" + o.orderNo).equals(e.data().get("paymentNo")) || o.amount.compareTo(
                    new java.math.BigDecimal(e.data().get("amount").toString())) != 0 || !o.currency.equals(
                    e.data().get("currency"))) {
                    throw new IllegalArgumentException("Payment fact mismatch");
                }
                if (Set.of("PAYMENT_PREPARING", "WAITING_PAYMENT", "CANCEL_REQUESTED").contains(o.status)) {
                    o.status = "PAID_PENDING_STOCK_CONFIRM";
                    o.paidAt = clock.instant();
                    emit(Topics.STOCK_CONFIRM_REQUESTED, o);
                } else if (Set.of("CANCEL_PENDING_STOCK_RELEASE", "CANCELLED").contains(o.status)) {
                    o.status = "REFUND_REQUIRED";
                    o.paidAt = clock.instant();
                    emit(Topics.REFUND_REQUESTED, o);
                }
            }
            case Topics.PAYMENT_CLOSE_CONFIRMED -> {
                if ("CANCEL_REQUESTED".equals(o.status)) {
                    o.status = "CANCEL_PENDING_STOCK_RELEASE";
                    emit(Topics.STOCK_RELEASE_REQUESTED, o);
                }
            }
            case Topics.STOCK_CONFIRM_FAILED -> {
                if ("PAID_PENDING_STOCK_CONFIRM".equals(o.status)) {
                    o.status = "REFUND_REQUIRED";
                    s.stockReleased = true;
                    emit(Topics.REFUND_REQUESTED, o);
                }
            }
            case Topics.STOCK_CONFIRMED -> {
                if ("PAID_PENDING_STOCK_CONFIRM".equals(o.status)) {
                    o.status = "COMPLETED";
                    s.status = "SUCCEEDED";
                }
            }
            case Topics.STOCK_RELEASED -> {
                if (!Set.of("CANCEL_PENDING_STOCK_RELEASE", "CANCELLED", "REFUND_REQUIRED", "REFUNDED")
                    .contains(o.status)) {
                    throw new IllegalStateException("Unexpected stock release");
                }
                s.stockReleased = true;
                if ("CANCEL_PENDING_STOCK_RELEASE".equals(o.status)) {
                    o.status = "CANCELLED";
                    o.cancelledAt = clock.instant();
                    s.status = "COMPENSATED";
                }
            }
            case Topics.REFUND_SUCCEEDED -> {
                if ("REFUND_REQUIRED".equals(o.status)) {
                    s.refundCompleted = true;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported order event");
        }
        if ("REFUND_REQUIRED".equals(o.status) && Boolean.TRUE.equals(s.stockReleased) && Boolean.TRUE.equals(
            s.refundCompleted)) {
            o.status = "REFUNDED";
            s.status = "COMPENSATED";
        }
        if ("REFUND_REQUIRED".equals(o.status)) {
            s.status = "COMPENSATING";
        }
        save(o, s);
        inbox.complete("order-service-events", e.eventId());
    }

    @GlobalLock
    @Transactional
    public void applyRecover(String no) {
        var o = lock(no);
        var s = sagas.selectById(no);
        if (s == null || s.nextRetryTime.isAfter(clock.instant()) || Set.of("COMPLETED", "CANCELLED", "REFUNDED")
            .contains(o.status)) {
            return;
        }
        switch (o.status) {
            case "PAYMENT_PREPARING" -> emit(Topics.PAYMENT_CREATE_REQUESTED, o);
            case "WAITING_PAYMENT" -> {
                if (!clock.instant().isBefore(o.paymentDeadline)) {
                    o.status = "CANCEL_REQUESTED";
                    emit(Topics.PAYMENT_CLOSE_REQUESTED, o);
                }
            }
            case "CANCEL_REQUESTED" -> emit(Topics.PAYMENT_CLOSE_REQUESTED, o);
            case "PAID_PENDING_STOCK_CONFIRM" -> emit(Topics.STOCK_CONFIRM_REQUESTED, o);
            case "CANCEL_PENDING_STOCK_RELEASE" -> emit(Topics.STOCK_RELEASE_REQUESTED, o);
            case "REFUND_REQUIRED" -> {
                if (!Boolean.TRUE.equals(s.refundCompleted)) {
                    emit(Topics.REFUND_REQUESTED, o);
                }
                if (!Boolean.TRUE.equals(s.stockReleased)) {
                    emit(Topics.STOCK_RELEASE_REQUESTED, o);
                }
            }
        }
        s.retryCount++;
        if (s.retryCount >= 100) {
            s.status = "ATTENTION_REQUIRED";
        }
        save(o, s);
    }

    private void save(TradeOrder o, OrderSaga s) {
        if (orders.updateById(o) != 1) {
            throw new IllegalStateException("ORDER_VERSION_CONFLICT");
        }
        s.currentStep = o.status;
        s.updatedAt = clock.instant();
        s.nextRetryTime = clock.instant().plusSeconds(30);
        sagas.updateById(s);
    }

    private void emit(String type, TradeOrder o) {
        emitted.append(type, o.orderNo,
            Map.of("orderNo", o.orderNo, "paymentNo", "P" + o.orderNo, "amount", o.amount.toPlainString(), "currency",
                o.currency));
    }
}
