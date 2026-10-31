package com.acme.ecommerce.common;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class MessagingConfiguration {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    var interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
    return interceptor;
  }
}
