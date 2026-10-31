package com.acme.ecommerce.common.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("message_transaction")
public class MessageTransaction {
  @TableId(type = IdType.INPUT)
  public String transactionId;

  public String payloadHash;
  public String payload;
  public String status;
  public Instant deadline;
  public Instant createdAt;
  public Instant completedAt;
}
