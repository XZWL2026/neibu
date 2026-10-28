package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.billing.application.ledger.BillingLedgerStore;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.routing.application.provider.*;
import com.xzwl.cloud.routing.application.model.*;
import com.xzwl.cloud.storage.infrastructure.*;
import com.xzwl.cloud.storage.application.file.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="MEDIA_TEST_MYSQL_URL",matches=".+")
class MediaServiceLoopMysqlTest {
  static final String TOKEN="integration-only-service-token-0000000000";
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final HttpClient http=HttpClient.newHttpClient();
  final List<ConfigurableApplicationContext> apps=new ArrayList<>();
  final List<String> schemas=new ArrayList<>();
  String root;
  JdbcTemplate admin;

  @Test void realServicePortsDebitArchiveRefundAndRecoverAcrossRestart() throws Exception {
    root=System.getenv("MEDIA_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    admin=new JdbcTemplate(new DriverManagerDataSource(root,dbUser(),dbPassword()));
    HttpServer identity=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    identity.createContext("/",exchange->{
      boolean userStatus=exchange.getRequestURI().getPath().startsWith("/internal/v1/users/");
      String body=userStatus ? "{\"id\":\"owner\",\"enabled\":true}" :
          "{\"authenticated\":true,\"roles\":[\"user\"],\"user\":{\"id\":\"owner\",\"username\":\"owner\"},\"csrfToken\":\"csrf\"}";
      exchange.getResponseHeaders().set("Content-Type","application/json");
      byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });identity.start();
    String identityUrl="http://127.0.0.1:"+identity.getAddress().getPort();
    HttpServer lossyBilling=null;
    try {
      var access=start(com.xzwl.cloud.access.TemplateApplication.class,"access",null,Map.of(
          "access.iam-service-url",identityUrl,"access.user-service-url",identityUrl,
          "access.user-internal-token",TOKEN,"access.key-encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
      var key=access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).ensurePrimary(
          "owner",com.xzwl.cloud.access.domain.key.AccountType.PERSONAL,"paid");
      var billing=start(com.xzwl.cloud.billing.TemplateApplication.class,"billing",null,Map.of(
          "iam.service-url",identityUrl,"billing.access-service-url",url(access),
          "billing.media-prices","[{\"accountType\":\"PERSONAL\",\"model\":\"model\",\"resolution\":\"standard\",\"pricePerUnit\":\"1.2345\"}]"));
      billing.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand(
          "paid","owner",key.id(),key.id(),Decimal4.of("100"),Decimal4.of("10"),Instant.now()));
      // Billing owns both the configured price and the original points-per-yuan snapshot.
      billing.getBean(SetRechargeRateHandler.class).handle(Decimal4.of("10.1234"),1);
      var routing=start(com.xzwl.cloud.routing.TemplateApplication.class,"routing",null,Map.of());
      routing.getBean(ConfigureProviderHandler.class).handle(new ConfigureProviderCommand(
          "provider","Provider","https://provider.example",true,"{}"));
      routing.getBean(ConfigureModelHandler.class).handle(new ConfigureModelCommand("model","provider","upstream",
          "{\"type\":\"IMAGE\",\"displayName\":\"Image\",\"resolutions\":[\"standard\"],\"aspectRatios\":[\"1:1\"],\"maxCount\":4,\"minDurationSeconds\":0,\"maxDurationSeconds\":0}"));
      var storage=start(com.xzwl.cloud.storage.TemplateApplication.class,"storage",null,Map.of(
          "storage.result-max-bytes","1024","spring.servlet.multipart.max-file-size","1024",
          "spring.servlet.multipart.max-request-size","2048","storage.archive.allowed-hosts","cdn.example",
          "storage.tos.region","test","storage.tos.endpoint","tos.example","storage.tos.bucket","test",
          "storage.tos.access-key-id","test","storage.tos.secret-access-key","test"));
      assertEquals(401,post(url(routing),"/internal/v1/tasks/unauthorized/route",Map.of("requestedModel","model"),Map.of()).statusCode());
      assertEquals(401,post(url(storage),"/internal/v1/files/media-results/archive-url",Map.of("ownerId","owner","sourceUrl","https://cdn.example/a.png"),Map.of()).statusCode());
      String billingUrl=url(billing);
      var loseFirstCharge=new java.util.concurrent.atomic.AtomicBoolean(true);
      lossyBilling=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      lossyBilling.createContext("/",exchange->{
        try {
          var forward=HttpRequest.newBuilder(URI.create(billingUrl+exchange.getRequestURI()))
              .header("Content-Type","application/json").header("X-Service-Token",
                  Objects.requireNonNull(exchange.getRequestHeaders().getFirst("X-Service-Token")))
              .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
          var received=http.send(forward,HttpResponse.BodyHandlers.ofByteArray());
          if(exchange.getRequestURI().getPath().equals("/internal/v1/billing/media-charges")&&received.statusCode()==200&&loseFirstCharge.compareAndSet(true,false)){exchange.close();return;}
          exchange.getResponseHeaders().set("Content-Type","application/json");
          exchange.sendResponseHeaders(received.statusCode(),received.body().length==0?-1:received.body().length);
          exchange.getResponseBody().write(received.body());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{exchange.close();}
      });lossyBilling.start();
      var mediaProps=Map.<String,Object>of("media.routing.url",url(routing),"media.storage.url",url(storage),
          "media.billing.url","http://127.0.0.1:"+lossyBilling.getAddress().getPort(),"media.iam.url",identityUrl,"media.worker.enabled","false",
          "media.worker.retry-interval","1ms","media.worker.poll-interval","1ms");
      var media=start(TemplateApplication.class,"media",null,mediaProps);
      BillingPort port=media.getBean(BillingPort.class);
      RoutingPort routes=media.getBean(RoutingPort.class);
      var request=new TaskRequest(MediaType.IMAGE,"model","cloud","standard",2,0,List.of());
      var route=routes.route("lost-charge-response","model");
      assertThrows(DependencyUnavailableException.class,()->port.charge("lost-charge-response","owner",request,route));
      var ledger=billing.getBean(BillingLedgerStore.class);
      assertEquals(Decimal4.of("975.0054"),ledger.balance(key.id()).availablePoints4());
      billing.getBean(SetRechargeRateHandler.class).handle(Decimal4.of("99"),2);
      // Replaying after the debit committed but its HTTP response was lost uses the original rate.
      access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).setEnabled(key.id(),false);
      ChargeReceipt receipt=port.charge("lost-charge-response","owner",request,route);
      assertEquals(new java.math.BigDecimal("24.9946"),receipt.points());
      assertEquals(receipt,port.charge("lost-charge-response","owner",request,route));
      assertThrows(DefinitiveRejectionException.class,()->port.charge("disabled-key-charge","owner",request,route));
      assertThrows(DefinitiveRejectionException.class,()->port.charge("lost-charge-response","other",request,route));
      assertThrows(DefinitiveRejectionException.class,()->port.charge("lost-charge-response","owner",
          new TaskRequest(MediaType.IMAGE,"model","cloud","standard",1,0,List.of()),route));
      port.refund("lost-charge-refund","owner",receipt,receipt.points());
      port.refund("lost-charge-refund","owner",receipt,receipt.points());
      assertEquals(Decimal4.of("1000"),ledger.balance(key.id()).availablePoints4());
      access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).setEnabled(key.id(),true);
      try(var pool=java.util.concurrent.Executors.newFixedThreadPool(6)) {
        var gate=new java.util.concurrent.CountDownLatch(1);
        var results=new ArrayList<java.util.concurrent.Future<ChargeReceipt>>();
        for(int i=0;i<6;i++)results.add(pool.submit(()->{gate.await();return port.charge("concurrent-charge","owner",request,route);}));
        gate.countDown();
        ChargeReceipt concurrent=results.getFirst().get(15,java.util.concurrent.TimeUnit.SECONDS);
        for(var result:results)assertEquals(concurrent,result.get(15,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Decimal4.of("755.5690"),ledger.balance(key.id()).availablePoints4());
        port.refund("concurrent-refund","owner",concurrent,concurrent.points());
      }
      assertThrows(DefinitiveRejectionException.class,()->port.charge("insufficient","owner",
          new TaskRequest(MediaType.VIDEO,"model","cloud","standard",1,600,List.of()),route));
      assertEquals(Decimal4.of("1000"),ledger.balance(key.id()).availablePoints4());
      assertEquals(0,billing.getBean(JdbcTemplate.class).queryForObject(
          "SELECT COUNT(*) FROM media_charge_receipt WHERE operation_id='insufficient'",Integer.class));
      String mediaDatabase=schemas.getLast();
      var admitted=post(url(media),"/api/v1/media/tasks",request,Map.of("Cookie","XZWL_SESSION=test","X-CSRF-TOKEN","csrf","Idempotency-Key","partial"));
      assertEquals(202,admitted.statusCode(),admitted.body());
      String task=json.readTree(admitted.body()).path("id").asText();
      assertEquals("SUBMIT_PENDING",json.readTree(admitted.body()).path("status").asText());
      media.close();
      media=start(TemplateApplication.class,"media",mediaDatabase,mediaProps);
      var worker=media.getBean(MediaTaskWorker.class);
      for(int i=0;i<6;i++) worker.process(task);
      var finished=media.getBean(MediaTaskService.class).get("owner",task);
      assertEquals(TaskStatus.PARTIAL_SUCCESS,finished.status());
      assertEquals(Decimal4.of("877.7845"),ledger.balance(key.id()).availablePoints4());
      var output=media.getBean(MediaTaskStore.class).results(task).getFirst();
      assertNull(output.sourceUrl());
      assertTrue(storage.getBean(MyBatisFileMetadataStore.class).findById(output.fileId()).isPresent());
      assertEquals(1,ledger.entries(key.id(),true,100).stream().filter(e->e.kind().equals("CONSUME")&&e.requestId().equals(task)).count());
      assertEquals(1,ledger.entries(key.id(),true,100).stream().filter(e->e.kind().equals("REFUND")&&e.requestId().equals(task+"-refund")).count());
      String billingDatabase=schemas.get(1);billing.close();
      billing=start(com.xzwl.cloud.billing.TemplateApplication.class,"billing",billingDatabase,Map.of("iam.service-url",identityUrl,"billing.access-service-url",url(access)));
      assertEquals(Decimal4.of("877.7845"),billing.getBean(BillingLedgerStore.class).balance(key.id()).availablePoints4());
    } finally {
      Collections.reverse(apps);for(var app:apps)app.close();identity.stop(0);
      if(lossyBilling!=null)lossyBilling.stop(0);
      for(String schema:schemas)admin.execute("DROP DATABASE "+schema);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named="PUBLIC_API_GATEWAY_TEST",matches="true")
  void publicGatewayApiKeyLoop() throws Exception {
    root=System.getenv("MEDIA_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    admin=new JdbcTemplate(new DriverManagerDataSource(root,dbUser(),dbPassword()));
    var userEnabled=new java.util.concurrent.atomic.AtomicBoolean(true);
    HttpServer identity=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    identity.createContext("/",exchange->{
      // Only IAM/User are fixtures: API secrets are checked by the real Access service and database.
      String body=exchange.getRequestURI().getPath().startsWith("/internal/v1/users/")
          ? "{\"id\":\""+exchange.getRequestURI().getPath().substring("/internal/v1/users/".length())+"\",\"enabled\":"+userEnabled.get()+"}"
          : "{\"authenticated\":false,\"roles\":[],\"user\":null}";
      byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });identity.start();
    Process gateway=null;
    try {
      String identityUrl="http://127.0.0.1:"+identity.getAddress().getPort();
      var access=start(com.xzwl.cloud.access.TemplateApplication.class,"access",null,Map.of(
          "access.iam-service-url",identityUrl,"access.user-service-url",identityUrl,
          "access.user-internal-token",TOKEN,"access.key-encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
      var keys=access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class);
      var api=com.xzwl.cloud.access.domain.key.AccountType.API;
      var owner=keys.ensurePrimary("owner",api,"paid-api");
      var other=keys.ensurePrimary("other",api,"paid-other");
      var poor=keys.ensurePrimary("poor",api,"paid-poor");
      var personal=keys.ensurePrimary("personal",com.xzwl.cloud.access.domain.key.AccountType.PERSONAL,"paid-personal");
      var billing=start(com.xzwl.cloud.billing.TemplateApplication.class,"billing",null,Map.of(
          "iam.service-url",identityUrl,"billing.access-service-url",url(access),
          "billing.media-prices",json.writeValueAsString(List.of(
              Map.of("accountType","API","model","model","resolution","standard","pricePerUnit","1"),
              Map.of("accountType","API","model","video","resolution","standard","pricePerUnit","1")))));
      billing.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand(
          "paid-api","owner",owner.id(),owner.id(),Decimal4.of("10"),Decimal4.of("10"),Instant.now()));
      billing.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand(
          "paid-other","other",other.id(),other.id(),Decimal4.of("10"),Decimal4.of("10"),Instant.now()));
      var routing=start(com.xzwl.cloud.routing.TemplateApplication.class,"routing",null,Map.of());
      routing.getBean(ConfigureProviderHandler.class).handle(new ConfigureProviderCommand(
          "provider","Provider","https://provider.example",true,"{}"));
      for(String model:List.of("model","video")) {
        boolean video=model.equals("video");
        routing.getBean(ConfigureModelHandler.class).handle(new ConfigureModelCommand(model,"provider","upstream",
            json.writeValueAsString(Map.of("type",video?"VIDEO":"IMAGE","displayName",model,
                "resolutions",List.of("standard"),"aspectRatios",List.of("1:1","16:9"),
                "maxCount",video?1:4,"minDurationSeconds",video?1:0,"maxDurationSeconds",video?5:0))));
      }
      var storage=start(com.xzwl.cloud.storage.TemplateApplication.class,"storage",null,Map.of(
          "storage.result-max-bytes","1024","spring.servlet.multipart.max-file-size","1024",
          "spring.servlet.multipart.max-request-size","2048","storage.archive.allowed-hosts","cdn.example",
          "storage.tos.region","test","storage.tos.endpoint","tos.example","storage.tos.bucket","test",
          "storage.tos.access-key-id","test","storage.tos.secret-access-key","test",
          "xzwl.api-key.access-url",url(access)));
      var media=start(TemplateApplication.class,"media",null,Map.of(
          "media.routing.url",url(routing),"media.storage.url",url(storage),"media.billing.url",url(billing),
          "media.iam.url",identityUrl,"media.worker.enabled","false","media.worker.poll-interval","1ms",
          "media.worker.retry-interval","1ms","xzwl.api-key.access-url",url(access)));
      int port;
      try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
      String base="http://127.0.0.1:"+port;
      Path repo=Path.of("../../..").toAbsolutePath().normalize();
      Path log=Path.of("target/public-api-gateway.log").toAbsolutePath();
      var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",
          repo.resolve("gateways/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar").toString(),
          "--server.address=127.0.0.1","--server.port="+port,
          "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false",
          "--spring.cloud.discovery.enabled=false","--management.health.defaults.enabled=false",
          "--gateway.upstreams.media="+url(media),"--gateway.upstreams.storage="+url(storage),
          "--gateway.upstreams.access="+url(access),"--logging.level.root=WARN");
      builder.environment().clear();
      gateway=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
      boolean ready=false;
      for(int i=0;i<200 && gateway.isAlive();i++) {
        try {if(get(base,"/actuator/health",null).statusCode()==200){ready=true;break;}}
        catch(IOException starting) { /* Bounded readiness poll of this test's own process. */ }
        Thread.sleep(100);
      }
      assertTrue(ready,"Gateway did not start; see "+log);
      String options="/api/v1/media/generation-options";
      var available=get(base,options,owner.secret());assertEquals(200,available.statusCode(),available.body());
      assertEquals(2,json.readTree(get(base,options,owner.secret()).body()).path("models").size());
      for(String invalid:List.of("invalid", "sk-_"+"0".repeat(64),personal.secret()))
        assertEquals(401,get(base,options,invalid).statusCode());
      keys.setEnabled(owner.id(),false);
      assertEquals(401,get(base,options,owner.secret()).statusCode());keys.setEnabled(owner.id(),true);
      userEnabled.set(false);assertEquals(401,get(base,options,owner.secret()).statusCode());userEnabled.set(true);
      assertEquals(404,post(base,"/internal/v1/access/authorize",Map.of(),Map.of()).statusCode());
      assertEquals(401,get(base,"/api/v1/access/keys/"+owner.id()+"/secret",owner.secret()).statusCode());
      assertEquals(401,get(base,"/api/v1/admin/media/provider-status",owner.secret()).statusCode());
      var image=new TaskRequest(MediaType.IMAGE,"model","cloud","standard",2,0,List.of());
      var quote=post(base,"/api/v1/media/quotes",Map.of("type","IMAGE","model","model","resolution","standard",
          "aspectRatio","1:1","count",2,"durationSeconds",0),Map.of("Authorization","Bearer "+owner.secret()));
      assertEquals(200,quote.statusCode(),quote.body());
      assertEquals(0,new java.math.BigDecimal(json.readTree(quote.body()).path("points").asText()).compareTo(new java.math.BigDecimal("20")));
      var headers=Map.of("Authorization","Bearer "+owner.secret(),"Idempotency-Key","public-partial");
      var admitted=post(base,"/api/v1/media/tasks",image,headers);
      assertEquals(202,admitted.statusCode(),admitted.body());
      assertEquals("SUBMIT_PENDING",json.readTree(admitted.body()).path("status").asText());
      String task=json.readTree(admitted.body()).path("id").asText();
      assertEquals(task,json.readTree(post(base,"/api/v1/media/tasks",image,headers).body()).path("id").asText());
      assertEquals(409,post(base,"/api/v1/media/tasks",new TaskRequest(MediaType.IMAGE,"model","changed","standard",2,0,List.of()),headers).statusCode());
      for(String path:List.of("/api/v1/media/tasks/"+task,"/api/v1/media/tasks/"+task+"/results"))
        assertEquals(404,get(base,path,other.secret()).statusCode());
      assertEquals(0,json.readTree(get(base,"/api/v1/media/tasks",other.secret()).body()).size());
      assertEquals(1,json.readTree(get(base,"/api/v1/media/tasks",owner.secret()).body()).size());
      var ledger=billing.getBean(BillingLedgerStore.class);
      assertEquals(Decimal4.of("80"),ledger.balance(owner.id()).availablePoints4());
      var worker=media.getBean(MediaTaskWorker.class);
      for(int i=0;i<10;i++)worker.process(task);
      var finished=get(base,"/api/v1/media/tasks/"+task,owner.secret());
      assertEquals("PARTIAL_SUCCESS",json.readTree(finished.body()).path("status").asText());
      assertFalse(finished.body().contains("provider.example"));assertFalse(finished.body().contains("sourceUrl"));
      var results=json.readTree(get(base,"/api/v1/media/tasks/"+task+"/results",owner.secret()).body());
      assertEquals(1,results.size());
      String filePath="/api/v1/files/"+results.get(0).path("fileId").asText()+"/access-url";
      var file=get(base,filePath,owner.secret());assertEquals(200,file.statusCode(),file.body());
      assertTrue(json.readTree(file.body()).path("url").asText().startsWith("https://cdn.example/"));
      assertFalse(json.readTree(file.body()).path("expiresAt").asText().isBlank());
      assertEquals(403,get(base,filePath,other.secret()).statusCode());
      assertEquals(Decimal4.of("90"),ledger.balance(owner.id()).availablePoints4());
      post(base,"/api/v1/media/tasks",image,headers);worker.process(task);
      assertEquals(Decimal4.of("90"),ledger.balance(owner.id()).availablePoints4());
      assertEquals(1,ledger.entries(owner.id(),true,100).stream().filter(e->e.kind().equals("CONSUME")&&e.requestId().equals(task)).count());
      assertEquals(1,ledger.entries(owner.id(),true,100).stream().filter(e->e.kind().equals("REFUND")&&e.requestId().equals(task+"-refund")).count());
      var video=new TaskRequest(MediaType.VIDEO,"video","cloud","standard",1,2,List.of());
      for(var request:List.of(video,new TaskRequest(MediaType.IMAGE,"model","fail","standard",1,0,List.of()))) {
        String id=json.readTree(post(base,"/api/v1/media/tasks",request,Map.of("Authorization","Bearer "+owner.secret(),
            "Idempotency-Key","public-"+request.prompt()+request.type())).body()).path("id").asText();
        for(int i=0;i<10;i++)worker.process(id);
        var result=json.readTree(get(base,"/api/v1/media/tasks/"+id,owner.secret()).body());
        assertEquals(request.type()==MediaType.VIDEO?"SUCCEEDED":"FAILED",result.path("status").asText());
        if(request.type()==MediaType.VIDEO) assertEquals(1,result.path("results").size());
        else assertEquals(1,ledger.entries(owner.id(),true,100).stream().filter(e->e.kind().equals("REFUND")&&e.requestId().equals(id+"-refund")).count());
      }
      assertEquals(Decimal4.of("70"),ledger.balance(owner.id()).availablePoints4());
      var insufficient=post(base,"/api/v1/media/tasks",image,Map.of("Authorization","Bearer "+poor.secret(),"Idempotency-Key","poor"));
      assertEquals(202,insufficient.statusCode(),insufficient.body());
      assertEquals("FAILED",json.readTree(insufficient.body()).path("status").asText());
      assertEquals(0,ledger.entries(poor.id(),true,100).size());
      assertEquals(400,post(base,"/api/v1/media/tasks",Map.of("type","IMAGE","model","model","prompt","cloud",
          "references",List.of(Map.of("httpsUrl","data:image/png;base64,AAAA"))),headers).statusCode());
      // Execute exactly the Python source displayed by the portal against this real Gateway.
      Path clientDir=java.nio.file.Files.createTempDirectory(Path.of("target"),"public-client-").toAbsolutePath();
      Path reference=clientDir.resolve("reference.png");
      java.nio.file.Files.write(reference,new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0});
      Path savedRequest=clientDir.resolve("request.json");
      var client=new ProcessBuilder("python3",repo.resolve("frontends/user-web/src/api-portal/public-api-example.py").toString(),
          "--request-file",savedRequest.toString(),"--reference-file",reference.toString());
      client.environment().put("XZWL_BASE_URL",base);client.environment().put("XZWL_API_KEY",owner.secret());
      client.redirectErrorStream(true).redirectOutput(clientDir.resolve("client.log").toFile());
      try(var scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
        scheduler.scheduleWithFixedDelay(worker::tick,0,10,java.util.concurrent.TimeUnit.MILLISECONDS);
        for(int i=0;i<2;i++) {
          Process run=client.start();
          try(var input=run.getOutputStream()){input.write("yes\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
          if(!run.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){run.destroyForcibly().waitFor();fail("Python example timed out");}
          assertEquals(0,run.exitValue(),java.nio.file.Files.readString(clientDir.resolve("client.log")));
          assertTrue(java.nio.file.Files.readString(clientDir.resolve("client.log")).contains("SUCCEEDED"));
        }
        scheduler.shutdownNow();
      }
      assertEquals(Decimal4.of("60"),ledger.balance(owner.id()).availablePoints4());
      var saved=json.readTree(java.nio.file.Files.readString(savedRequest));
      String referenceId=saved.path("body").path("references").get(0).path("fileId").asText();
      assertEquals(200,get(base,"/api/v1/files/"+referenceId+"/access-url",owner.secret()).statusCode());
      assertEquals(403,get(base,"/api/v1/files/"+referenceId+"/access-url",other.secret()).statusCode());
      var otherAdmitted=post(base,"/api/v1/media/tasks",image,
          Map.of("Authorization","Bearer "+other.secret(),"Idempotency-Key","other-valid"));
      assertEquals(202,otherAdmitted.statusCode(),otherAdmitted.body());
      assertEquals("SUBMIT_PENDING",json.readTree(otherAdmitted.body()).path("status").asText());
      var otherBalance=ledger.balance(other.id()).availablePoints4();
      assertEquals(Decimal4.of("80"),otherBalance);
      int otherEntries=ledger.entries(other.id(),true,100).size();
      var stolenReference=post(base,"/api/v1/media/tasks",saved.path("body"),
          Map.of("Authorization","Bearer "+other.secret(),"Idempotency-Key","stolen-reference"));
      assertEquals(202,stolenReference.statusCode(),stolenReference.body());
      assertEquals("FAILED",json.readTree(stolenReference.body()).path("status").asText());
      assertEquals(otherEntries,ledger.entries(other.id(),true,100).size());
      assertEquals(otherBalance,ledger.balance(other.id()).availablePoints4());
      System.out.println("PUBLIC_API_GATEWAY: valid/invalid/disabled/wrong-kind/disabled-user, permissions, IMAGE partial refund, VIDEO, results, ownership, idempotency, insufficient balance, full refund, Base64 rejection PASSED; provider/storage/identity SIMULATED");
    } finally {
      if(gateway!=null){gateway.destroy();if(!gateway.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))gateway.destroyForcibly().waitFor();}
      Collections.reverse(apps);for(var app:apps)app.close();identity.stop(0);
      for(String schema:schemas)admin.execute("DROP DATABASE "+schema);
    }
  }
  HttpResponse<String> get(String base,String path,String secret)throws Exception {
    var req=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(2)).GET();
    if(secret!=null)req.header("Authorization","Bearer "+secret);
    return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
  }
  ConfigurableApplicationContext start(Class<?> type,String service,String existing,Map<String,Object> extra) {
    String schema=existing;
    if(schema==null){schema="release_"+service+"_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");schemas.add(schema);}
    var props=new HashMap<String,Object>(extra);
    props.put("spring.datasource.url",root+schema);props.put("spring.datasource.username",dbUser());props.put("spring.datasource.password",dbPassword());
    props.put("xzwl.api-key.service-token",TOKEN);
    props.put("server.port","0");props.put("server.address","127.0.0.1");props.put("abp.internal-token",TOKEN);
    props.put("media.routing.token",TOKEN);props.put("media.storage.token",TOKEN);props.put("media.billing.token",TOKEN);
    props.put("routing.media-token",TOKEN);props.put("storage.media-token",TOKEN);
    Path module=Path.of("..","..",service+"-service").toAbsolutePath().normalize();
    Path migrations=module.resolve(service+"-infrastructure/src/main/resources/db");
    props.put("spring.flyway.locations",service.equals("media")?"filesystem:"+migrations.resolve("media-migration/common")+",filesystem:"+migrations.resolve("media-migration/mysql"):"filesystem:"+migrations.resolve("migration"));
    var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("test",props));
    var builder=new SpringApplicationBuilder(type).environment(env).web(WebApplicationType.SERVLET);
    builder.initializers(ctx->{
      var beans=(GenericApplicationContext)ctx;
      if(service.equals("media")) beans.registerBean("fixtureProvider",ProviderPort.class,()->new ProviderPort(){
        public void requireAvailable(RouteSnapshot r){}
        public String submit(String id,String owner,TaskRequest q,RouteSnapshot r){return id;}
        public ProviderPoll poll(String id,TaskRequest q,RouteSnapshot r){return q.prompt().equals("fail")?ProviderPoll.failed():ProviderPoll.completed(List.of(new ProviderOutput(0,"https://cdn.example/"+(q.type()==MediaType.VIDEO?"a.mp4":"a.png"))));}
      },bd->bd.setPrimary(true));
      if(service.equals("storage")) {
        var objects=mock(TosPrivateObjectStorage.class);
        var storedObjects=new java.util.concurrent.ConcurrentHashMap<String,PrivateObjectStorage.StoredObject>();
        when(objects.putPrivate(anyString(),anyString(),anyLong(),any())).thenAnswer(call->{
          ((InputStream)call.getArgument(3)).readAllBytes();
          storedObjects.put(call.getArgument(0),new PrivateObjectStorage.StoredObject(call.getArgument(0),call.getArgument(1),call.getArgument(2),"etag"));return "etag";
        });
        when(objects.signGet(anyString(),anyLong())).thenReturn("https://cdn.example/signed-result");
        when(objects.head(anyString())).thenAnswer(call->storedObjects.get(call.getArgument(0)));
        var remote=mock(HttpsRemoteFileSource.class);
        when(remote.open(any())).thenAnswer(call->new RemoteFileSource.RemoteFile(){
          boolean video=call.getArgument(0).toString().endsWith(".mp4");
          public String mimeType(){return video?"video/mp4":"image/png";}public long contentLength(){return 12;}
          public InputStream body(){return new ByteArrayInputStream(video?new byte[]{0,0,0,20,'f','t','y','p','i','s','o','m'}:new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0});}public void close(){}
        });
        beans.registerBean("fixtureObjects",TosPrivateObjectStorage.class,()->objects,bd->bd.setPrimary(true));
        beans.registerBean("fixtureRemote",HttpsRemoteFileSource.class,()->remote,bd->bd.setPrimary(true));
      }
    });
    var app=builder.run("--spring.config.location=file:"+module.resolve(service+"-bootstrap/src/main/resources/application.yml"),"--logging.level.root=WARN","--spring.main.banner-mode=off");apps.add(app);return app;
  }
  HttpResponse<String> post(String base,String path,Object body,Map<String,String> headers)throws Exception {
    var req=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    headers.forEach(req::header);return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
  }
  static String url(ConfigurableApplicationContext app){return "http://127.0.0.1:"+((ServletWebServerApplicationContext)app).getWebServer().getPort();}
  static String dbUser(){return System.getenv().getOrDefault("MEDIA_TEST_MYSQL_USERNAME","root");}
  static String dbPassword(){return System.getenv().getOrDefault("MEDIA_TEST_MYSQL_PASSWORD","");}
}
