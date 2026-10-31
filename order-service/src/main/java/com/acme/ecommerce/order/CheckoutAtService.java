package com.acme.ecommerce.order;

import com.acme.ecommerce.common.StockContract;
import com.acme.ecommerce.order.mapper.TradeOrderMapper;
import com.acme.ecommerce.order.model.TradeOrder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class CheckoutAtService {
    private final TradeOrderMapper orders;
    private final StockClient stock;
    private final Clock clock;
    private final OrderFlow flow;
    private final CheckoutMessagePublisher publisher;

    public CheckoutAtService(TradeOrderMapper orders, StockClient stock, Clock clock, OrderFlow flow,
                             CheckoutMessagePublisher publisher) {
        this.orders = orders;
        this.stock = stock;
        this.clock = clock;
        this.flow = flow;
        this.publisher = publisher;
    }

    @GlobalTransactional(name = "checkout-reserve", timeoutMills = 10000, rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public String create(String key, String hash, OrderController.CreateRequest request) {
        if (!RootContext.inGlobalTransaction()) {
            throw new IllegalStateException("AT must be enabled for checkout");
        }
        var o = new TradeOrder();
        o.orderNo = "O" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        o.userId = request.userId();
        o.skuId = request.skuId();
        o.quantity = request.quantity();
        o.amount = request.amount();
        o.currency = request.currency();
        o.status = "PAYMENT_PREPARING";
        o.version = 0L;
        o.paymentDeadline = clock.instant().plusSeconds(900);
        o.createdAt = clock.instant();
        o.globalXid = RootContext.getXID();
        o.requestHash = hash;
        o.idempotencyKey = key;
        orders.insert(o);
        var reserved = stock.reserve(new StockContract.Reserve(o.orderNo, o.skuId, o.quantity));
        if (o.amount.compareTo(reserved.amount()) != 0 || !o.currency.equals(reserved.currency())) {
            throw new IllegalArgumentException("PRICE_MISMATCH");
        }
        flow.initialize(o);
        publisher.publish(o);
        return o.orderNo;
    }
}
