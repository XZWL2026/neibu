package com.xzwl.cloud.media;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.infrastructure.task.MediaSettlementOutbox;
import com.xzwl.cloud.media.infrastructure.http.HttpBillingPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
@EnabledIfEnvironmentVariable(named="MEDIA_TEST_MYSQL_URL",matches=".+")
class MediaSettlementMysqlTest {
  @Test void terminalSaveAndDeliveryAreAtomicAndRetryAcrossRestart() throws Exception {
    var fixture=new MediaServiceLoopMysqlTest();fixture.root=System.getenv("MEDIA_TEST_MYSQL_URL");
    assertTrue(fixture.root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    fixture.admin=new JdbcTemplate(new DriverManagerDataSource(fixture.root,MediaServiceLoopMysqlTest.dbUser(),MediaServiceLoopMysqlTest.dbPassword()));
    var received=new java.util.concurrent.CopyOnWriteArrayList<String>();var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/billing/media-settlements",exchange->{
      received.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(fail.getAndSet(false)?503:200,-1);exchange.close();
    });server.start();
    var props=Map.<String,Object>of("media.worker.enabled",false);
    Instant now=Instant.now();
    try {
      var app=fixture.start(TemplateApplication.class,"media",null,props);
      var store=app.getBean(MediaTaskStore.class);var jdbc=app.getBean(JdbcTemplate.class);
      var original=task("success",TaskStatus.ARCHIVING,now);
      store.create(original);var lease=store.claim(original.id(),"lease",now,now.plusSeconds(60)).orElseThrow();
      jdbc.execute("ALTER TABLE media_settlement_delivery ADD CONSTRAINT reject_delivery CHECK (task_id='impossible')");
      assertThrows(RuntimeException.class,()->store.save(lease,original.advance(TaskStatus.SUCCEEDED,original.data(),now),List.of(),now));
      assertEquals(TaskStatus.ARCHIVING,store.find(original.id()).orElseThrow().status());
      jdbc.execute("ALTER TABLE media_settlement_delivery DROP CHECK reject_delivery");
      store.save(lease,original.advance(TaskStatus.SUCCEEDED,original.data(),now),List.of(),now);
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_settlement_delivery",Integer.class));
      var port=new HttpBillingPort(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
      app.getBean(MediaSettlementOutbox.class).deliver(port,now);
      assertEquals(TaskStatus.SUCCEEDED,store.find(original.id()).orElseThrow().status());
      assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_settlement_delivery WHERE completed=true",Integer.class));
      String schema=fixture.schemas.getFirst();app.close();
      app=fixture.start(TemplateApplication.class,"media",schema,props);
      app.getBean(MediaSettlementOutbox.class).deliver(port,now.plusSeconds(11));
      assertEquals(2,received.size());assertEquals(received.get(0),received.get(1));
      var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.getFirst());
      assertEquals("100.0000",body.get("chargedPoints4").asText());assertTrue(body.get("chargedPoints4").isTextual());
      assertEquals(1,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM media_settlement_delivery WHERE completed=true",Integer.class));
    } finally {server.stop(0);fixture.apps.forEach(org.springframework.context.ConfigurableApplicationContext::close);fixture.schemas.forEach(s->fixture.admin.execute("DROP DATABASE "+s));}
  }
  static MediaTask task(String id,TaskStatus status,Instant now) {
    var request=new TaskRequest(MediaType.IMAGE,"model","prompt","standard",1,0,List.of());
    var charge=new ChargeReceipt(id,new BigDecimal("10"),new BigDecimal("10"),new BigDecimal("100"));
    return new MediaTask(id,"owner",id,status,new TaskData(request,null,charge,null,null,null,null),now,now.plusSeconds(600),now,0);
  }
}
