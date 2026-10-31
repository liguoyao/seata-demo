package com.acme.ecommerce.stock;

import com.acme.ecommerce.common.*;
import com.acme.ecommerce.stock.mapper.SkuStockMapper;
import com.acme.ecommerce.stock.mapper.StockReservationMapper;
import com.acme.ecommerce.stock.model.SkuStock;
import com.acme.ecommerce.stock.model.StockReservation;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import java.util.Map;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
  private java.util.List<EventEnvelope> planConsume(EventEnvelope e) {
    if (inbox.wasDelivered("stock-service-events", e.eventId())) return java.util.List.of();
    var r = reservations.selectById(e.aggregateId());
    if (r == null) return java.util.List.of();
    if (Topics.STOCK_CONFIRM_REQUESTED.equals(e.eventType()) && "RELEASED".equals(r.status))
      return java.util.List.of(
          emitted.event(
              Topics.STOCK_CONFIRM_FAILED,
              e.aggregateId(),
              Map.of("orderNo", e.aggregateId(), "reason", "ALREADY_RELEASED")));
    return java.util.List.of(
        emitted.event(
            Topics.STOCK_CONFIRM_REQUESTED.equals(e.eventType())
                ? Topics.STOCK_CONFIRMED
                : Topics.STOCK_RELEASED,
            e.aggregateId(),
            Map.of("orderNo", e.aggregateId())));
  }

  public void consume(EventEnvelope e) {
    transactions.execute(() -> planConsume(e), () -> self.getObject().applyConsume(e));
  }

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<StockService> self;

  @org.springframework.beans.factory.annotation.Autowired
  private LocalMessageTransactions transactions;

  private final SkuStockMapper stocks;
  private final StockReservationMapper reservations;
  private final Inbox inbox;
  private final BusinessEvents emitted;
  private final Clock clock;

  public StockService(
      SkuStockMapper stocks,
      StockReservationMapper reservations,
      Inbox inbox,
      BusinessEvents emitted,
      Clock clock) {
    this.stocks = stocks;
    this.reservations = reservations;
    this.inbox = inbox;
    this.emitted = emitted;
    this.clock = clock;
  }

  @GlobalLock
  @Transactional(rollbackFor = Exception.class)
  public StockContract.Reserved reserve(StockContract.Reserve request) {
    if (request.quantity() < 1) {
      throw new IllegalArgumentException("Quantity must be positive");
    }
    var sku =
        stocks.selectOne(
            new QueryWrapper<SkuStock>().eq("sku_id", request.skuId()).last("FOR UPDATE"));
    if (sku == null) {
      throw new IllegalArgumentException("SKU_NOT_FOUND");
    }
    var previous = reservations.selectById(request.orderNo());
    if (previous != null) {
      if (previous.skuId != request.skuId()
          || previous.quantity != request.quantity()
          || !"RESERVED".equals(previous.status)) {
        throw new IllegalStateException("RESERVATION_CONFLICT");
      }
      return new StockContract.Reserved(
          sku.unitPrice.multiply(java.math.BigDecimal.valueOf(request.quantity())), sku.currency);
    }
    if (sku.availableQty < request.quantity()) {
      throw new IllegalStateException("INSUFFICIENT_STOCK");
    }
    sku.availableQty -= request.quantity();
    sku.reservedQty += request.quantity();
    sku.updatedAt = clock.instant();
    if (stocks.updateById(sku) != 1) {
      throw new IllegalStateException("STOCK_VERSION_CONFLICT");
    }
    var r = new StockReservation();
    r.orderNo = request.orderNo();
    r.skuId = request.skuId();
    r.quantity = request.quantity();
    r.status = "RESERVED";
    r.createdAt = clock.instant();
    r.updatedAt = r.createdAt;
    reservations.insert(r);
    return new StockContract.Reserved(
        sku.unitPrice.multiply(java.math.BigDecimal.valueOf(request.quantity())), sku.currency);
  }

  @GlobalLock
  @Transactional(rollbackFor = Exception.class)
  public void applyConsume(EventEnvelope e) {
    var r =
        reservations.selectOne(
            new QueryWrapper<StockReservation>()
                .eq("order_no", e.aggregateId())
                .last("FOR UPDATE"));
    if (r == null) {
      throw new IllegalStateException("Reservation missing; cannot ACK a stock command");
    }
    if (!inbox.firstDelivery("stock-service-events", e)) {
      return;
    }
    boolean confirm = Topics.STOCK_CONFIRM_REQUESTED.equals(e.eventType());
    if (!confirm && !Topics.STOCK_RELEASE_REQUESTED.equals(e.eventType())) {
      throw new IllegalArgumentException("Unsupported stock command");
    }
    String target = confirm ? "CONFIRMED" : "RELEASED";
    if (confirm && "RELEASED".equals(r.status)) {
      emitted.append(
          Topics.STOCK_CONFIRM_FAILED,
          e.aggregateId(),
          Map.of("orderNo", e.aggregateId(), "reason", "ALREADY_RELEASED"));
      inbox.complete("stock-service-events", e.eventId());
      return;
    }
    if (!"RESERVED".equals(r.status) && !target.equals(r.status)) {
      throw new IllegalStateException("Stock terminal-state conflict: " + r.status);
    }
    if ("RESERVED".equals(r.status)) {
      var sku =
          stocks.selectOne(new QueryWrapper<SkuStock>().eq("sku_id", r.skuId).last("FOR UPDATE"));
      if (sku.reservedQty < r.quantity) {
        throw new IllegalStateException("Stock invariant violated");
      }
      sku.reservedQty -= r.quantity;
      if (confirm) {
        sku.soldQty += r.quantity;
      } else {
        sku.availableQty += r.quantity;
      }
      sku.updatedAt = clock.instant();
      if (stocks.updateById(sku) != 1) {
        throw new IllegalStateException("STOCK_VERSION_CONFLICT");
      }
      r.status = target;
      r.updatedAt = clock.instant();
      reservations.updateById(r);
    }
    emitted.append(
        confirm ? Topics.STOCK_CONFIRMED : Topics.STOCK_RELEASED,
        e.aggregateId(),
        Map.of("orderNo", e.aggregateId()));
    inbox.complete("stock-service-events", e.eventId());
  }
}
