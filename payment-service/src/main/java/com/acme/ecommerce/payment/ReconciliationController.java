package com.acme.ecommerce.payment;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
/** Full-history, settled-success snapshot reconciliation, not a daily settlement adapter. */
@RestController
@RequestMapping("/internal/reconciliation")
public class ReconciliationController {
 private final JdbcTemplate db;
 public ReconciliationController(JdbcTemplate db) { this.db=db; }
 public record Row(String kind,String businessNo,String channelReference,BigDecimal amount,String currency) {}
 public record Request(String channel,String merchant,boolean completeHistory,List<Row> rows) {}
 @PostMapping @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> reconcile(@RequestBody Request request) {
  if(!request.completeHistory() || request.channel()==null || request.merchant()==null || request.rows()==null || request.rows().size()>100000)
   throw new IllegalArgumentException("Complete settled history required (maximum 100000 rows)");
  var local=new HashMap<String,Row>();
  db.query("SELECT payment_no,channel_trade_no,amount,currency FROM payment_order WHERE status='SUCCESS' AND channel_code=? AND merchant_id=?",rs->{
   var row=new Row("PAYMENT",rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getString(4));local.put(row.kind()+":"+row.businessNo(),row);
  },request.channel(),request.merchant());
  db.query("SELECT r.refund_no,r.channel_refund_no,r.amount,p.currency FROM refund_order r JOIN payment_order p ON r.payment_no=p.payment_no WHERE r.status='SUCCEEDED' AND p.channel_code=? AND p.merchant_id=?",rs->{
   var row=new Row("REFUND",rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getString(4));local.put(row.kind()+":"+row.businessNo(),row);
  },request.channel(),request.merchant());
  String run=UUID.randomUUID().toString(); int differences=0;
  var seen=new HashSet<String>();
  for(var row:request.rows()) {
   if(row==null || !Set.of("PAYMENT","REFUND").contains(row.kind()) || row.businessNo()==null || row.amount()==null || row.currency()==null) throw new IllegalArgumentException("Invalid statement row");
   String key=row.kind()+":"+row.businessNo();
   if(!seen.add(key)) { save(run,key,"DUPLICATE_CHANNEL",row.toString()); differences++; continue; }
   Row expected=local.remove(key);
   String reason=expected==null?"CHANNEL_ONLY":expected.amount().compareTo(row.amount())!=0 || !Objects.equals(expected.currency(),row.currency()) || !Objects.equals(expected.channelReference(),row.channelReference())?"FACT_MISMATCH":null;
   if(reason!=null) {save(run,key,reason,row.toString());differences++;}
  }
  for(var entry:local.entrySet()) {save(run,entry.getKey(),"LOCAL_ONLY",entry.getValue().toString());differences++;}
  db.update("INSERT INTO reconciliation_run(id,channel,merchant,differences,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",run,request.channel(),request.merchant(),differences);
  return Map.of("runId",run,"differences",differences);
 }
 private void save(String run,String key,String reason,String detail) {
  db.update("INSERT INTO reconciliation_difference(run_id,business_key,reason,detail,status) VALUES(?,?,?,?,'OPEN')",run,key,reason,detail);
 }
 @GetMapping("/{runId}") public List<Map<String,Object>> differences(@PathVariable String runId) {
  return db.queryForList("SELECT * FROM reconciliation_difference WHERE run_id=?",runId);
 }
}
