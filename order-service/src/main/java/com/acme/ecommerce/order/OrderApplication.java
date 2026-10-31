package com.acme.ecommerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.acme.ecommerce.order", "com.acme.ecommerce.common"})
@org.mybatis.spring.annotation.MapperScan("com.acme.ecommerce.order.mapper")
@org.mybatis.spring.annotation.MapperScan("com.acme.ecommerce.common.mapper")
@EnableScheduling
public class OrderApplication {
  public static void main(String[] args) {
    SpringApplication.run(OrderApplication.class, args);
  }
}
