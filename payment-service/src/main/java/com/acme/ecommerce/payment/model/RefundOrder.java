package com.acme.ecommerce.payment.model;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("refund_order")
public class RefundOrder {
  @TableId(type = IdType.INPUT)
  public String refundNo;

  public String paymentNo;
  public String orderNo;
  public BigDecimal amount;
  public String status;
  public String channelRefundNo;
  public Instant createdAt;
  public Instant updatedAt;
}
