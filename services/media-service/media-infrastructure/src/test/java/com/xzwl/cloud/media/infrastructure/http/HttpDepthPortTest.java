package com.xzwl.cloud.media.infrastructure.http;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.application.task.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
class HttpDepthPortTest {
  @Test void transfersOnlyOwnerAuthorizedSourceBytesAndTreatsBusyAsRetryable() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    var uploaded=new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath();byte[] body;int status=200;
      if(path.equals("/source")) body="source-bytes".getBytes();
      else if(path.startsWith("/internal")) {
        String input=new String(exchange.getRequestBody().readAllBytes());assertTrue(input.contains("owner"));assertTrue(input.contains("source"));
        body=("{\"url\":\""+base+"/source\",\"expiresAt\":\"2026-09-11T12:00:00Z\",\"mimeType\":\"video/mp4\",\"sizeBytes\":12,\"durationSeconds\":30.1,\"width\":640,\"height\":360,\"fps\":25}").getBytes();
      } else {
        assertEquals("Bearer "+"t".repeat(32),exchange.getRequestHeaders().getFirst("Authorization"));
        if(exchange.getRequestMethod().equals("GET")) {status=404;body=new byte[0];}
        else {
          assertEquals("owner",exchange.getRequestHeaders().getFirst("X-Owner-Id"));
          assertEquals("source",exchange.getRequestHeaders().getFirst("X-Source-File-Id"));
          assertEquals("480p",exchange.getRequestHeaders().getFirst("X-Resolution"));
          uploaded.set(new String(exchange.getRequestBody().readAllBytes()));status=503;body=new byte[0];
        }
      }
      exchange.getResponseHeaders().add("Content-Type",path.equals("/source")?"video/mp4":"application/json");
      exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      var port=new HttpDepthPort(RestClient.builder().baseUrl(base).build(),base,"t".repeat(32),new ObjectMapper(),Duration.ofSeconds(5));
      var request=new TaskRequest(MediaType.VIDEO,"depth-anything-v2-small","depth","source","480p",1,31,
          List.of(new MediaReference("source",null,MediaReference.Kind.VIDEO,30.1)),null,TaskOperation.DEPTH);
      var task=new MediaTask("task","owner","key",TaskStatus.ROUTING,new TaskData(request,null,null,null,null,null,null),Instant.EPOCH,Instant.MAX,Instant.EPOCH,0);
      assertThrows(DependencyUnavailableException.class,()->port.submit(task));
      assertEquals("source-bytes",uploaded.get());
    } finally {server.stop(0);}
  }

  @Test void authenticatesWorkerAndArchivesBinaryWithoutExposingUrl() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    var calls=new ArrayList<String>();
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath(); calls.add(exchange.getRequestMethod()+" "+path);
      if(path.startsWith("/jobs")) assertEquals("Bearer "+"t".repeat(32),exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body;
      if(path.endsWith("/result")) { body="video-result".getBytes();exchange.getResponseHeaders().add("Content-Type","video/mp4"); }
      else if(path.startsWith("/jobs")) body="{\"status\":\"COMPLETE\",\"owner\":\"owner\",\"sourceFileId\":\"source\",\"resolution\":\"480p\"}".getBytes();
      else {
        String multipart=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(multipart.contains("video-result"));assertTrue(multipart.contains("owner"));
        body="{\"fileId\":\"stored\",\"purpose\":\"MEDIA_RESULT\",\"sizeBytes\":12}".getBytes();
      }
      if(!path.endsWith("/result")) exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });
    server.start();
    try {
      var port=new HttpDepthPort(RestClient.builder().baseUrl(base).build(),base,"t".repeat(32),new ObjectMapper(),Duration.ofSeconds(5));
      var request=new TaskRequest(MediaType.VIDEO,"depth-anything-v2-small","depth","source","480p",1,31,
          List.of(new MediaReference("source",null,MediaReference.Kind.VIDEO,30.1)),null,TaskOperation.DEPTH);
      var task=new MediaTask("task","owner","key",TaskStatus.RUNNING,new TaskData(request,null,null,null,null,null,null),Instant.EPOCH,Instant.MAX,Instant.EPOCH,0);
      port.submit(task); // Existing job replay never fetches source bytes or submits again.
      assertEquals("COMPLETE",port.poll(task));
      assertEquals("stored",port.archive(task));
      assertEquals(List.of("GET /jobs/task","GET /jobs/task","GET /jobs/task","GET /jobs/task/result","POST /internal/v1/files/media-results"),calls);
    } finally { server.stop(0); }
  }
}
