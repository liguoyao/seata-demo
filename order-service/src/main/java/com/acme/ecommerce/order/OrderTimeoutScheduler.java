package com.acme.ecommerce.order;

import com.acme.ecommerce.order.mapper.*;
import com.acme.ecommerce.order.model.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workers.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutScheduler {
  private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);
  private final OrderSagaMapper sagas;
  private final OrderFlow flow;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired private RecoveryLease leases;

  public OrderTimeoutScheduler(OrderSagaMapper sagas, OrderFlow flow, Clock clock) {
    this.sagas = sagas;
    this.flow = flow;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${order.recover-ms:1000}")
  public void scan() {
    for (var s :
        sagas.selectList(
            new QueryWrapper<OrderSaga>()
                .le("next_retry_time", clock.instant())
                .notIn("status", "SUCCEEDED", "COMPENSATED")
                .orderByAsc("next_retry_time")
                .last("LIMIT 100"))) {
      String token = leases.claim(s.orderNo);
      if (token == null) continue;
      try {
        flow.cancel(s.orderNo);
        flow.recover(s.orderNo);
      } catch (Exception ex) {
        sagas.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<OrderSaga>()
            .eq("order_no", s.orderNo).eq("next_retry_time", s.nextRetryTime)
            .set("next_retry_time", clock.instant().plusSeconds(
                java.util.concurrent.ThreadLocalRandom.current().nextLong(30, 61))));
        log.warn("Saga recovery failed for {}", s.orderNo, ex);
      } finally { leases.release(s.orderNo, token); }
    }
  }
}
