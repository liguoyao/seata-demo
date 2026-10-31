package com.acme.ecommerce.order.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("order_saga")
public class OrderSaga {
  @TableId(type = IdType.INPUT)
  public String orderNo;

  public String currentStep;
  public String status;
  public Integer retryCount;
  public Instant nextRetryTime;
  public Instant createdAt;
  public Instant updatedAt;
  public Boolean stockReleased;
  public Boolean refundCompleted;
}
