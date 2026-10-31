package com.acme.ecommerce.stock.model;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("sku_stock")
public class SkuStock {
  @TableId(type = IdType.INPUT)
  public Long skuId;

  public BigDecimal unitPrice;
  public String currency;
  public Long availableQty;
  public Long reservedQty;
  public Long soldQty;
  @Version public Long version;
  public Instant updatedAt;
}
