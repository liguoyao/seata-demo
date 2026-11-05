package com.acme.ecommerce.order;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class RecoveryLease {
 private final JdbcTemplate db;
 public RecoveryLease(JdbcTemplate db) { this.db=db; }
 public String claim(String order) {
  String token=UUID.randomUUID().toString();
  return db.update("UPDATE order_saga SET lease_token=?, lease_until=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 120 SECOND) WHERE order_no=? AND (lease_until IS NULL OR lease_until < UTC_TIMESTAMP(6))",token,order)==1?token:null;
 }
 public void release(String order,String token) {
  db.update("UPDATE order_saga SET lease_token=NULL,lease_until=NULL WHERE order_no=? AND lease_token=?",order,token);
 }
}
