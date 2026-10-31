package com.acme.ecommerce.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("stock_reservation")
public class StockReservation {
  @TableId(type = IdType.INPUT)
  public String orderNo;

  public Long skuId;
  public Integer quantity;
  public String status;
  public Instant createdAt;
  public Instant updatedAt;
}
