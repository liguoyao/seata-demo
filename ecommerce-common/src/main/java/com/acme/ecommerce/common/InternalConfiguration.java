package com.acme.ecommerce.common;

import org.apache.seata.core.context.RootContext;
import org.apache.seata.integration.http.jakarta.JakartaTransactionPropagationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InternalConfiguration implements WebMvcConfigurer {
  @Bean
  public RestTemplate restTemplate(@Value("${internal.token:}") String token) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(3000);
    var http = new RestTemplate(factory);
    http.getInterceptors()
        .add(
            (request, body, execution) -> {
              if (token.isBlank()) {
                throw new IllegalStateException("Internal service token is required");
              }
              request.getHeaders().set("X-Service-Token", token);
              String xid = RootContext.getXID();
              if (xid != null) {
                request.getHeaders().set(RootContext.KEY_XID, xid);
              }
              return execution.execute(request, body);
            });
    return http;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new JakartaTransactionPropagationInterceptor())
        .addPathPatterns("/internal/stock/**");
  }
}
