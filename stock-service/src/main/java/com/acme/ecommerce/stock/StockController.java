package com.acme.ecommerce.stock;

import com.acme.ecommerce.common.StockContract;
import org.apache.seata.core.context.RootContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/stock")
public class StockController {
  private final StockService stock;

  public StockController(StockService stock) {
    this.stock = stock;
  }

  @PostMapping("/reserve")
  public StockContract.Reserved reserve(@RequestBody StockContract.Reserve r) {
    if (!RootContext.inGlobalTransaction()) {
      throw new IllegalStateException("Stock reservation requires an AT transaction");
    }
    return stock.reserve(r);
  }
}
