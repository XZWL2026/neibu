package com.xzwl.cloud.media.infrastructure.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.BillingPort;
import com.xzwl.cloud.media.domain.task.MediaTask;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable terminal facts are inserted by the same transaction that saves the task. */
public final class MediaSettlementOutbox {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public MediaSettlementOutbox(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
  public void record(MediaTask task,Instant at) {
    if(!task.status().terminal() || task.data().charge()==null) return;
    var charge=task.data().charge();
    var refund=task.data().refundPoints()==null?java.math.BigDecimal.ZERO:task.data().refundPoints();
    var event=new BillingPort.Settlement(task.id(),task.ownerId(),task.status().name(),charge.points().toPlainString(),refund.setScale(4).toPlainString(),at);
    try { jdbc.update("INSERT INTO media_settlement_delivery(task_id,payload) VALUES(?,?)",task.id(),json.writeValueAsString(event)); }
    catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("cannot encode settlement",failure);}
  }
  public void deliver(BillingPort billing,Instant now) {
    for(var row:jdbc.queryForList("SELECT task_id,payload FROM media_settlement_delivery WHERE completed=false AND next_attempt_at<=? ORDER BY next_attempt_at,task_id LIMIT 100",now.toEpochMilli())) {
      String id=(String)row.get("task_id");
      try {
        billing.settle(json.readValue((String)row.get("payload"),BillingPort.Settlement.class));
        jdbc.update("UPDATE media_settlement_delivery SET completed=true WHERE task_id=?",id);
      } catch(Exception failure) {
        jdbc.update("UPDATE media_settlement_delivery SET next_attempt_at=? WHERE task_id=? AND completed=false",now.plusSeconds(10).toEpochMilli(),id);
      }
    }
  }
}
