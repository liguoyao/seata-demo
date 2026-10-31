package com.acme.ecommerce.common.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("inbox_event")
public class InboxEvent {
  @TableId(type = IdType.AUTO)
  public Long id;

  public String consumerName;
  public String eventId;
  public String eventType;
  public String aggregateId;
  public String payloadHash;
  public String status;
  public Instant receivedAt;
  public Instant consumedAt;
}
