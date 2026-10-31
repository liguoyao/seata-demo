package com.acme.ecommerce.payment.model;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("payment_order")
public class PaymentOrder {
  @TableId(type = IdType.INPUT)
  public String orderNo;

  public String paymentNo;
  public BigDecimal amount;
  public String currency;
  public String status;
  public String channelCode;
  public String merchantId;
  public String channelTradeNo;
  @Version public Long version;
  public Boolean closeRequested;
  public Instant createdAt;
  public Instant updatedAt;
}
