package com.acme.ecommerce.payment;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class ReconciliationMetrics {
 public ReconciliationMetrics(MeterRegistry registry,JdbcTemplate db) {
  registry.gauge("trading.reconciliation.open",db,d->d.queryForObject("SELECT COUNT(*) FROM reconciliation_difference WHERE status='OPEN'",Long.class).doubleValue());
  registry.gauge("trading.channel.unresolved",db,d->d.queryForObject("SELECT COUNT(*) FROM channel_operation WHERE status IN ('UNKNOWN','RUNNING') AND updated_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 5 MINUTE)",Long.class).doubleValue());
 }
}
