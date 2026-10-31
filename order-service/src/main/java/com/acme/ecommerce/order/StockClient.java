package com.acme.ecommerce.order;

import com.acme.ecommerce.common.StockContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class StockClient {
  private final RestTemplate http;
  private final String url;

  public StockClient(
      RestTemplate http, @Value("${stock.base-url:http://localhost:8082}") String url) {
    this.http = http;
    this.url = url;
  }

  public StockContract.Reserved reserve(StockContract.Reserve request) {
    var result =
        http.postForObject(url + "/internal/stock/reserve", request, StockContract.Reserved.class);
    if (result == null) throw new IllegalStateException("Empty stock response");
    return result;
  }
}
