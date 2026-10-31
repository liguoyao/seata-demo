package com.acme.ecommerce.stock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.acme.ecommerce.stock", "com.acme.ecommerce.common"})
@org.mybatis.spring.annotation.MapperScan("com.acme.ecommerce.stock.mapper")
@org.mybatis.spring.annotation.MapperScan("com.acme.ecommerce.common.mapper")
@EnableScheduling
public class StockApplication {
  public static void main(String[] args) {
    SpringApplication.run(StockApplication.class, args);
  }
}
