package com.acme.ecommerce.payment.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("payment_channel_event")
public class ChannelEvent {
  @TableId(type = IdType.INPUT)
  public String eventKey;

  public String paymentNo;
  public String payloadHash;
  public Instant createdAt;
}
