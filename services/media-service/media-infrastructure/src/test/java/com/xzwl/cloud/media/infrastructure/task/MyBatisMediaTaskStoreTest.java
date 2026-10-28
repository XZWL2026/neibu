package com.xzwl.cloud.media.infrastructure.task;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

@SpringBootTest(classes=MyBatisMediaTaskStoreTest.Config.class,properties={
    "spring.datasource.url=jdbc:h2:mem:media;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.locations=classpath:db/media-migration/common"})
class MyBatisMediaTaskStoreTest {
  @Configuration @EnableAutoConfiguration @MapperScan("com.xzwl.cloud.media.infrastructure.task")
  @Import(MyBatisMediaTaskStore.class) static class Config {}
  @Autowired MyBatisMediaTaskStore store;
  @Autowired TaskMapper tasks;
  @Autowired StateMapper states;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  final Instant now=Instant.parse("2026-09-07T01:00:00Z");
  MediaTask task(String owner,String key) {
    return new MediaTask(UUID.randomUUID().toString(),owner,key,TaskStatus.ROUTING,
        new TaskData(new TaskRequest(MediaType.IMAGE,"model","prompt","1024",1,0,List.of()),null,null,null,null,null,null),
        now,now.plusSeconds(600),now,0);
  }

  @Test void terminalSettlementDeliveryHasDurableStorage() {
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='media_settlement_delivery'", Integer.class));
  }

  @Test void terminalOutboxKeepsPartialFailureAndFreeAmounts() throws Exception {
    for(var outcome:List.of(TaskStatus.PARTIAL_SUCCESS,TaskStatus.FAILED,TaskStatus.TIMED_OUT,TaskStatus.SUCCEEDED)) {
      var seed=task(UUID.randomUUID().toString(),"terminal");
      var points=outcome==TaskStatus.SUCCEEDED?java.math.BigDecimal.ZERO:new java.math.BigDecimal("100");
      var refund=outcome==TaskStatus.PARTIAL_SUCCESS?new java.math.BigDecimal("25"):points;
      var data=seed.data().withCharge(new ChargeReceipt(seed.id(),points,java.math.BigDecimal.ONE,points));
      if(outcome!=TaskStatus.SUCCEEDED)data=data.withOutcome(outcome,refund,"PROVIDER_FAILED");
      var source=new MediaTask(seed.id(),seed.ownerId(),seed.idempotencyKey(),outcome==TaskStatus.SUCCEEDED?TaskStatus.ARCHIVING:TaskStatus.REFUND_PENDING,data,now,seed.deadlineAt(),now,0);
      store.create(source);var lease=store.claim(source.id(),"terminal",now,now.plusSeconds(30)).orElseThrow();
      store.save(lease,source.advance(outcome,data,now),List.of(),now);
      var event=new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbc.queryForObject("SELECT payload FROM media_settlement_delivery WHERE task_id=?",String.class,source.id()));
      assertEquals(outcome.name(),event.get("status").asText());
      assertEquals(refund.setScale(4).toPlainString(),event.get("refundedPoints4").asText());
      assertEquals(points.setScale(4).toPlainString(),event.get("chargedPoints4").asText());
    }
  }

  @Test void regenerationPersistsSourceBindingAndRollsBackWhenHistoryCannotBeHidden() {
    String owner=UUID.randomUUID().toString();
    MediaTask seed=task(owner,"source");
    MediaTask source=store.create(new MediaTask(seed.id(),owner,seed.idempotencyKey(),TaskStatus.FAILED,seed.data(),now,seed.deadlineAt(),now,0));
    MediaTask replacement=task(owner,"regenerate");
    // Fail the final write after the replacement insert: both task and state must roll back.
    jdbc.execute("ALTER TABLE media_task ADD CONSTRAINT keep_history CHECK (id <> '"+source.id()+"' OR history_deleted = FALSE)");
    try {
      assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.regenerate(source.id(),replacement));
      assertTrue(store.find(replacement.id()).isEmpty());
      assertTrue(store.replayRegeneration(owner,source.id(),replacement.idempotencyKey()).isEmpty());
      assertEquals(1,store.list(owner,10,null,false).size());
      assertEquals(0,states.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StateRow>().eq("task_id",replacement.id())));
    } finally { jdbc.execute("ALTER TABLE media_task DROP CONSTRAINT keep_history"); }
    assertEquals(replacement,store.regenerate(source.id(),replacement));
    assertEquals(source,store.find(source.id()).orElseThrow());
    assertEquals(List.of(replacement),store.list(owner,10,null,false));
    assertEquals(source.id(),tasks.selectById(replacement.id()).regenerationSourceId);
    assertEquals(replacement,store.regenerate(source.id(),task(owner,"regenerate")));
    assertThrows(TaskConflictException.class,()->store.regenerate(source.id(),task(owner,"different-key")));
    assertThrows(TaskConflictException.class,()->store.create(task(owner,"regenerate")));
    assertEquals(1,states.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StateRow>().eq("task_id",replacement.id())));
  }

  @Test void depthTransitionsPersistWithoutCommercialStages() {
    var request=new TaskRequest(MediaType.VIDEO,"depth-anything-v2-small","depth","source","480p",1,31,
        List.of(new MediaReference("source",null,MediaReference.Kind.VIDEO,30.1)),null,TaskOperation.DEPTH);
    var task=store.create(new MediaTask(UUID.randomUUID().toString(),"depth-owner","depth-key",TaskStatus.ROUTING,
        new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(600),now,0));
    for(var next:List.of(TaskStatus.RUNNING,TaskStatus.ARCHIVING,TaskStatus.SUCCEEDED)) {
      var lease=store.claim(task.id(),"depth",now,now.plusSeconds(30)).orElseThrow();
      var advanced=lease.task().advance(next,lease.task().data(),now);
      store.save(lease,advanced,next==TaskStatus.SUCCEEDED?List.of(new TaskResult(task.id(),0,null,"depth-file")):List.of(),now);
    }
    assertEquals(TaskStatus.SUCCEEDED,store.find(task.id()).orElseThrow().status());
    assertEquals("depth-file",store.results(task.id()).getFirst().fileId());
    assertNull(store.find(task.id()).orElseThrow().data().charge());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_settlement_delivery WHERE task_id=?",Integer.class,task.id()));
  }

  @Test void ordinaryQueriesAndConcurrentIdempotencyUseTheDatabase() throws Exception {
    String owner=UUID.randomUUID().toString();
    try(var pool=Executors.newFixedThreadPool(4)) {
      List<Future<MediaTask>> inserts=new ArrayList<>();
      for(int i=0;i<8;i++) inserts.add(pool.submit(() -> store.create(task(owner,"same-key"))));
      Set<String> ids=new HashSet<>(); for(var result:inserts) ids.add(result.get().id());
      assertEquals(1,ids.size()); assertEquals(1,store.list(owner,10,null,false).size());
      assertTrue(store.list("another-owner",10,null,false).isEmpty());
      assertEquals(1,states.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StateRow>().eq("task_id",ids.iterator().next())));
    }
  }

  @Test void leaseExpiryRecoversTaskAndFencesStaleWritersWithAtomicHistory() throws Exception {
    MediaTask original=store.create(task(UUID.randomUUID().toString(),"lease"));
    MediaTaskStore.Lease first=store.claim(original.id(),"first",now,now.plusSeconds(10)).orElseThrow();
    assertTrue(store.claim(original.id(),"competitor",now,now.plusSeconds(10)).isEmpty());
    MediaTaskStore.Lease recovered=store.claim(original.id(),"restarted-worker",now.plusSeconds(11),now.plusSeconds(30)).orElseThrow();
    MediaTask next=original.advance(TaskStatus.CHARGE_PENDING,original.data(),now.plusSeconds(12));
    assertThrows(LostLeaseException.class,() -> store.save(first,next,List.of(),now.plusSeconds(12)));
    store.save(recovered,next,List.of(),now.plusSeconds(12));
    assertEquals(next,store.find(original.id()).orElseThrow());
    assertEquals(2,states.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StateRow>().eq("task_id",original.id())));
    assertThrows(LostLeaseException.class,() -> store.save(recovered,next,List.of(),now.plusSeconds(12)));
  }

  @Test void resultFailureRollsBackTaskAndStateTransition() {
    MediaTask original=store.create(task(UUID.randomUUID().toString(),"rollback"));
    var lease=store.claim(original.id(),"worker",now,now.plusSeconds(30)).orElseThrow();
    var next=original.advance(TaskStatus.CHARGE_PENDING,original.data(),now);
    var wrongOwnerResult=new TaskResult("different-task",0,"https://cdn.example/a",null);
    assertThrows(IllegalArgumentException.class,() -> store.save(lease,next,List.of(wrongOwnerResult),now));
    assertEquals(original,store.find(original.id()).orElseThrow());
  }

  @Test void persistsIndependentOutputFailuresAndSuccessfulFileIds() {
    MediaTask task=store.create(task(UUID.randomUUID().toString(),"outputs"));
    var first=new TaskResult(task.id(),0,"https://cdn.example/first",null);
    var second=new TaskResult(task.id(),1,"https://cdn.example/second",null);
    var lease=store.claim(task.id(),"output-worker",now,now.plusSeconds(30)).orElseThrow();
    store.save(lease,task.advance(TaskStatus.ROUTING,task.data(),now),List.of(first,second),now);
    lease=store.claim(task.id(),"output-worker",now,now.plusSeconds(30)).orElseThrow();
    store.save(lease,lease.task().advance(TaskStatus.ROUTING,task.data(),now),List.of(first.failed()),now);
    lease=store.claim(task.id(),"output-worker",now,now.plusSeconds(30)).orElseThrow();
    store.save(lease,lease.task().advance(TaskStatus.ROUTING,task.data(),now),List.of(second.archived("file-2")),now);
    assertEquals("ARCHIVE_REJECTED",store.results(task.id()).get(0).failureCode());
    assertNull(store.results(task.id()).get(0).sourceUrl());
    assertEquals("file-2",store.results(task.id()).get(1).fileId());
    assertNull(store.results(task.id()).get(1).sourceUrl());
  }
}
