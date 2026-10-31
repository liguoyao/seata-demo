package com.acme.ecommerce.order.model;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("orders")
public class TradeOrder {
  @TableId(type = IdType.INPUT)
  public String orderNo;

  public Long userId;
  public Long skuId;
  public Integer quantity;
  public BigDecimal amount;
  public String currency;
  public String status;
  @Version public Long version;
  public Instant paymentDeadline;
  public Instant createdAt;
  public Instant paidAt;
  public Instant cancelledAt;
  public String globalXid;
  public String requestHash;
  public String idempotencyKey;
}
