package com.acme.ecommerce.common.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("message_failure")
public class MessageFailure {
  @TableId(type = IdType.AUTO)
  public Long id;

  public String consumerGroup;
  public String eventId;
  public String payload;
  public String lastError;
  public Integer attempts;
  public Instant updatedAt;
}
