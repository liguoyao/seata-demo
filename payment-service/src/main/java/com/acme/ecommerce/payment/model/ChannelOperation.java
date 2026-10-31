package com.acme.ecommerce.payment.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("channel_operation")
public class ChannelOperation {
  @TableId(type = IdType.INPUT)
  public String operationId;

  public String orderNo;
  public String type;
  public String status;
  public Integer attempts;
  public Instant nextRetryTime;
  public String leaseToken;
  public Instant leaseUntil;
  public String lastError;
  public Instant createdAt;
  public Instant updatedAt;
}
