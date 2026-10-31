package com.acme.ecommerce.common;

import java.math.BigDecimal;

public final class StockContract {
  private StockContract() {}

  public record Reserve(String orderNo, long skuId, int quantity) {}

  public record Reserved(BigDecimal amount, String currency) {}
}
