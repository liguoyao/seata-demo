package com.acme.ecommerce.order;

import com.acme.ecommerce.common.Hashing;
import com.acme.ecommerce.order.mapper.TradeOrderMapper;
import com.acme.ecommerce.order.model.TradeOrder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final CheckoutAtService checkout;
    private final OrderFlow flow;
    private final TradeOrderMapper orders;

    public OrderController(CheckoutAtService checkout, OrderFlow flow, TradeOrderMapper orders) {
        this.checkout = checkout;
        this.flow = flow;
        this.orders = orders;
    }

    public record CreateRequest(Long userId, Long skuId, Integer quantity, BigDecimal amount, String currency) {
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String key, @RequestBody CreateRequest request) {
        if (key.isBlank() || key.length() > 100 || request.userId() == null || request.skuId() == null || request.quantity() == null || request.quantity() < 1 || request.amount() == null || request.amount()
            .signum() <= 0 || request.amount().scale() > 2 || request.currency() == null || !request.currency()
            .matches("[A-Z]{3}")) {
            return ResponseEntity.badRequest().body("INVALID_REQUEST");
        }
        String hash = Hashing.sha256(
            request.userId() + "|" + request.skuId() + "|" + request.quantity() + "|" + request.amount()
                .stripTrailingZeros().toPlainString() + "|" + request.currency());
        var previous = find(request.userId(), key);
        if (previous != null) {
            return existing(previous, hash);
        }
        try {
            String no = checkout.create(key, hash, request);
            return ResponseEntity.accepted().body(orders.selectById(no));
        } catch (DuplicateKeyException duplicate) {
            previous = find(request.userId(), key);
            if (previous == null) {
                throw duplicate;
            }
            return existing(previous, hash);
        }
    }

    private TradeOrder find(long user, String key) {
        return orders.selectOne(new QueryWrapper<TradeOrder>().eq("user_id", user).eq("idempotency_key", key));
    }

    private ResponseEntity<?> existing(TradeOrder order, String hash) {
        return hash.equals(order.requestHash) ? ResponseEntity.accepted().body(order)
            : ResponseEntity.status(409).body("IDEMPOTENCY_KEY_CONFLICT");
    }

    @GetMapping("/{orderNo}")
    public TradeOrder get(@PathVariable String orderNo) {
        return orders.selectById(orderNo);
    }

    @PostMapping("/{orderNo}/cancel")
    public TradeOrder cancel(@PathVariable String orderNo) {
        flow.cancel(orderNo);
        return orders.selectById(orderNo);
    }
}
