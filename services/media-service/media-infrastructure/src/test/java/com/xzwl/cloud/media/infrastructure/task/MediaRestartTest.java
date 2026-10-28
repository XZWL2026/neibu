package com.xzwl.cloud.media.infrastructure.task;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class MediaRestartTest {
  @TempDir Path directory;
  ConfigurableApplicationContext open() {
    return new SpringApplicationBuilder(MyBatisMediaTaskStoreTest.Config.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url=jdbc:h2:file:"+directory.resolve("media")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "--spring.datasource.username=sa","--spring.datasource.password=",
        "--spring.flyway.locations=classpath:db/media-migration/common","--spring.main.banner-mode=off","--logging.level.root=WARN");
  }
  @Test void aNewApplicationRecoversAnExpiredLeaseAndCompletesWithoutAnotherDebitOrSubmission() {
    Instant now=Instant.parse("2026-09-07T00:00:00Z");
    String id;
    try(var first=open()) {
      MediaTaskStore store=first.getBean(MediaTaskStore.class);
      TaskPolicy policy=new TaskPolicy(Duration.ofHours(1),Duration.ofHours(6),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
      MediaTask task=new MediaTaskService(store,Clock.fixed(now,ZoneOffset.UTC),policy)
          .submit("owner","restart-request",new TaskRequest(MediaType.VIDEO,"model","prompt","720p",1,5,List.of()));
      id=task.id();
      var route=new RouteSnapshot(id,"model","provider","Provider","https://provider.example","frozen-model","{}","{}",now);
      var receipt=new ChargeReceipt("charge",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN);
      for(TaskStatus next:List.of(TaskStatus.CHARGE_PENDING,TaskStatus.SUBMIT_PENDING,TaskStatus.RUNNING)) {
        var lease=store.claim(id,"first-process",now,now.plusSeconds(30)).orElseThrow();
        TaskData data=switch(next) {
          case CHARGE_PENDING -> lease.task().data().withRoute(route);
          case SUBMIT_PENDING -> lease.task().data().withCharge(receipt);
          default -> lease.task().data().withProviderTask("existing-upstream-task");
        };
        store.save(lease,lease.task().advance(next,data,now),List.of(),now);
      }
      store.claim(id,"crashed-process",now,now.plusSeconds(30)).orElseThrow();
    }
    try(var second=open()) {
      MediaTaskStore store=second.getBean(MediaTaskStore.class);
      Clock recovered=Clock.fixed(now.plusSeconds(31),ZoneOffset.UTC);
      BillingPort billing=new BillingPort() {
        public ChargeReceipt charge(String op,String owner,TaskRequest request,RouteSnapshot route) { throw new AssertionError("must not debit again"); }
        public void refund(String op,String owner,ChargeReceipt receipt,BigDecimal points) { throw new AssertionError("successful task must not refund"); }
      };
      ProviderPort provider=new ProviderPort() {
        public void requireAvailable(RouteSnapshot route) { throw new AssertionError("must use persisted route"); }
        public String submit(String op,String owner,TaskRequest request,RouteSnapshot route) { throw new AssertionError("must not submit again"); }
        public ProviderPoll poll(String upstream,TaskRequest request,RouteSnapshot route) {
          assertEquals("existing-upstream-task",upstream); assertEquals("frozen-model",route.providerModel());
          return ProviderPoll.completed(List.of(new ProviderOutput(0,"https://cdn.example/video.mp4")));
        }
      };
      MediaTaskWorker worker=new MediaTaskWorker(store,(task,model) -> { throw new AssertionError("must not reroute"); },
          billing,provider,new StoragePort() {
      public java.util.List<MediaReference> resolveReferences(String owner, java.util.List<MediaReference> references) { return references; }
      public String archive(String owner,String id,int index,String url) { return "recovered-file"; }
    },recovered,
          new TaskPolicy(Duration.ofHours(1),Duration.ofHours(6),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10));
      worker.tick(); worker.tick(); worker.tick();
      assertEquals(TaskStatus.SUCCEEDED,store.find(id).orElseThrow().status());
      assertEquals("recovered-file",store.results(id).getFirst().fileId());
      assertNull(store.results(id).getFirst().sourceUrl());
    }
  }
}
