package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore.Connection;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderHttpPolicyTest {
  @Test void retriesOnlyTransientGetResponsesAndNeverPost() throws Exception {
    for(String method:List.of("GET","POST")) for(int status:List.of(503,408,429,401,403)) {
      AtomicInteger calls=new AtomicInteger();
      HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{
        int code=calls.incrementAndGet()==1?status:200;
        byte[] body="{\"ok\":true}".getBytes();exchange.sendResponseHeaders(code,body.length);
        exchange.getResponseBody().write(body);exchange.close();
      });
      server.start();
      try {
        ProviderHttp http=new ProviderHttp(HttpClient.newHttpClient(),new ObjectMapper(),Duration.ofSeconds(2));
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        if(method.equals("GET") && Set.of(503,408,429).contains(status)) {
          assertTrue(http.call(method,url,Map.of(),null,1,3).path("ok").asBoolean());
          assertEquals(2,calls.get());
        } else {
          assertThrows(RuntimeException.class,()->http.call(method,url,Map.of(),null,1,3));
          assertEquals(1,calls.get());
        }
      } finally {server.stop(0);}
    }
  }
  @Test void protocolFailureDoesNotRetryAndRetryCountIsBounded() throws Exception {
    for(String response:List.of("not-json","[]","{}")) {
      AtomicInteger calls=new AtomicInteger();
      HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{calls.incrementAndGet();byte[] body=response.getBytes();exchange.sendResponseHeaders(response.equals("{}")?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();});
      server.start();
      try {
        var http=new ProviderHttp(HttpClient.newHttpClient(),new ObjectMapper(),Duration.ofSeconds(2));
        assertThrows(DependencyUnavailableException.class,()->http.call("GET","http://127.0.0.1:"+server.getAddress().getPort(),Map.of(),null,1,2));
        assertEquals(response.equals("{}")?3:1,calls.get());
      } finally {server.stop(0);}
    }
  }
  @Test void retriesShareOneBudgetCappedByServerTimeout() throws Exception {
    AtomicInteger calls=new AtomicInteger();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
    server.createContext("/",exchange->{
      calls.incrementAndGet();
      try {Thread.sleep(170);byte[] body="{}".getBytes();exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);}
      catch(Exception ignored) {} finally {exchange.close();}
    });server.start();
    try {
      var http=new ProviderHttp(HttpClient.newHttpClient(),new ObjectMapper(),Duration.ofMillis(260));
      long started=System.nanoTime();
      assertThrows(DependencyUnavailableException.class,()->http.call("GET","http://127.0.0.1:"+server.getAddress().getPort(),Map.of(),null,Duration.ofSeconds(10),3));
      assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<650);
      assertEquals(2,calls.get());
    } finally {server.stop(0);executor.shutdownNow();}
  }
  @Test void modelPolicyInheritsOverridesAndExecutionPlanRetainsFrozenValues() throws Exception {
    var json=new ObjectMapper();
    var settings=ProviderSettings.from(Map.of(),json).withConnection(new Connection(1,"ark","https://ark.cn-beijing.volces.com/api/v3","fixture",90,1));
    String capability="{\"type\":\"IMAGE\",\"resolutions\":[\"2K\"],\"aspectRatios\":[\"1:1\"],\"maxCount\":4,\"minDurationSeconds\":0,\"maxDurationSeconds\":0,\"sizes\":{\"2K\":{\"1:1\":\"2048x2048\"}}}";
    TaskRequest request=new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","2K",1,0,List.of());
    RouteSnapshot route=new RouteSnapshot("task","image","ark","Ark","https://ark.cn-beijing.volces.com/api/v3","doubao-seedream-4-5-251128","{}",capability,Instant.EPOCH);
    var inherited=settings.plan(request,route);assertEquals(90,inherited.timeoutSeconds());assertEquals(1,inherited.retryCount());
    var configured=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(capability);configured.putObject("requestPolicy").put("timeoutSeconds",25).put("retryCount",0);
    var override=settings.plan(request,new RouteSnapshot("other","image","ark","Ark",route.providerEndpoint(),route.providerModel(),"{}",configured.toString(),Instant.EPOCH));
    assertEquals(25,override.timeoutSeconds());assertEquals(0,override.retryCount());
    var frozen=json.readValue(json.writeValueAsString(override),ProviderSettings.Plan.class);
    assertEquals(override,frozen);
    assertEquals(25,frozen.withSchedule(new ProviderSettings.Schedule(1,List.of(2L),1,1)).timeoutSeconds());
    assertEquals(90,inherited.timeoutSeconds());
    configured.putObject("requestPolicy").putNull("timeoutSeconds").put("retryCount",3);
    var partial=settings.plan(request,new RouteSnapshot("partial","image","ark","Ark",route.providerEndpoint(),route.providerModel(),"{}",configured.toString(),Instant.EPOCH));
    assertEquals(90,partial.timeoutSeconds());assertEquals(3,partial.retryCount());
    var old=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(override);
    old.remove(List.of("timeoutSeconds","retryCount"));
    var legacy=json.treeToValue(old,ProviderSettings.Plan.class);
    assertNull(legacy.timeoutSeconds());assertNull(legacy.retryCount());
  }
}
