package com.acme.ecommerce.common.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("replay_audit")
public class ReplayAudit {
  @TableId(type = IdType.AUTO)
  public Long id;

  public String eventId;
  public String operatorName;
  public String reason;
  public Instant createdAt;
}
