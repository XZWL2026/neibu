package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore;
import com.xzwl.cloud.media.application.task.StoragePort;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.infrastructure.provider.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ArchiveIdentityTest {
  @Test void productionWiringKeepsIdentityAcrossFreshCosSignaturesAndPreservesStages() throws Exception {
    var json=new ObjectMapper();
    var bodies=new CopyOnWriteArrayList<JsonNode>();
    var tokens=new CopyOnWriteArrayList<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/files/media-results/archive-url",exchange->{
      try(exchange) {
        bodies.add(json.readTree(exchange.getRequestBody()));
        tokens.add(exchange.getRequestHeaders().getFirst("X-Service-Token"));
        byte[] response="{\"fileId\":\"stored-file\",\"purpose\":\"MEDIA_RESULT\",\"sizeBytes\":12}".getBytes();
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
      }
    });
    server.start();
    try {
      var time=new AtomicReference<>(Instant.parse("2026-09-29T00:00:00Z"));
      Clock clock=new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
      };
      var journal=mock(ProviderJournal.class);
      when(journal.executionPlan("task-1")).thenReturn(new ProviderSettings.Plan(
          "https://provider.example","model",MediaType.VIDEO,"480p","720p","16:9",
          "https://mps.example","ap-guangzhou","fixture-123","ap-guangzhou",720));
      var config=mock(MpsConfigurationStore.class);
      when(config.load()).thenReturn(new MpsConfigurationStore.Configuration(1,"fixture-id","fixture-secret",
          "ap-guangzhou","fixture-123","ap-guangzhou","/output/",720,1080,2160));
      var settings=ProviderSettings.from(Map.of(),json);
      var mps=new TencentMpsAdapter(null,clock); // Signing is local; no provider HTTP is used.
      String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
      var storage=new MediaConfiguration().mediaStoragePort(settings,mps,config,journal,endpoint,
          "test-service-token",Duration.ofSeconds(2),Duration.ofSeconds(2));
      String source="https://fixture-123.cos.ap-guangzhou.myqcloud.com/output/final.mp4";
      assertEquals("stored-file",storage.archive("owner","task-1",0,source));
      time.set(time.get().plusSeconds(10));
      var restarted=new MediaConfiguration().mediaStoragePort(settings,mps,config,journal,endpoint,
          "test-service-token",Duration.ofSeconds(2),Duration.ofSeconds(2));
      assertEquals("stored-file",restarted.archive("owner","task-1",0,source));
      restarted.archive("owner","task-1",0,"https://provider.example/native.mp4?token=unchanged",StoragePort.ArchiveStage.NATIVE);
      restarted.archive("owner","task-1",0,source.replace("final","intermediate"),StoragePort.ArchiveStage.ENHANCEMENT);
      assertEquals(List.of("task-1:RESULT:0","task-1:RESULT:0","task-1:NATIVE:0","task-1:ENHANCEMENT:0"),
          bodies.stream().map(body->body.path("archiveKey").asText()).toList());
      String first=bodies.get(0).path("sourceUrl").asText(),second=bodies.get(1).path("sourceUrl").asText();
      assertTrue(first.contains("q-signature="));assertTrue(second.contains("q-signature="));
      assertNotEquals(first,second);
      assertEquals("https://provider.example/native.mp4?token=unchanged",bodies.get(2).path("sourceUrl").asText());
      assertEquals(Collections.nCopies(4,"test-service-token"),tokens);
      assertTrue(bodies.stream().allMatch(body->body.path("ownerId").asText().equals("owner")));
    } finally { server.stop(0); }
  }
}
