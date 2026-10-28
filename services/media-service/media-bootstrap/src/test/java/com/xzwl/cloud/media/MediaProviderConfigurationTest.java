package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.RouteSnapshot;
import com.xzwl.cloud.media.infrastructure.provider.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:media-provider-config;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=",
    "spring.flyway.locations=classpath:db/media-migration/common","media.worker.enabled=false"})
@Import(MediaProviderConfigurationTest.EmptyEnvironment.class)
class MediaProviderConfigurationTest {
  @TestConfiguration static class EmptyEnvironment {
    @Bean @Primary ProviderSettings fixtureProviderSettings(ObjectMapper json) { return ProviderSettings.from(Map.of(),json); }
  }
  @Autowired ProviderPort provider;
  @Autowired JdbcTemplate jdbc;
  @Test void statusIncludesDatabaseOnlyProvidersWithoutAnEnvironmentBinding() {
    var store=new JdbcArkConnectionStore(jdbc,"",new com.xzwl.cloud.media.application.provider.ArkConnectionStore.Connection(0,"","",""),java.util.Set.of("ark.example"));
    var empty=ProviderSettings.from(Map.of(),new ObjectMapper());
    var status=new MediaConfiguration().liveProviderStatus(empty,store);
    assertFalse(status.get().configured());
    try {
      store.save(0,"database-only","https://ark.example","database-fixture-key");
      assertTrue(status.get().configured());
      assertTrue(status.get().available());
      assertFalse(status.get().toString().contains("database-fixture-key"));
    } finally { jdbc.update("DELETE FROM media_provider_connection WHERE provider_id='database-only'"); }
  }
  @Test void statusUsesProcessConfigurationWithoutExposingCredentials() throws Exception {
    var settings=ProviderSettings.from(Map.of("VOLC_ARK_API_KEY","secret-fixture-not-for-response",
        "MEDIA_ARK_BASE_URL","https://ark.example","MEDIA_ARK_PROVIDER_ID","ark"),new ObjectMapper());
    var status=new MediaConfiguration().providerStatus(settings).get();
    assertTrue(status.configured());assertTrue(status.available());
    String response=new ObjectMapper().writeValueAsString(status);
    assertFalse(response.contains("secret-fixture-not-for-response"));
    assertFalse(response.contains("https://ark.example"));
    var missing=new MediaConfiguration().providerStatus(ProviderSettings.from(Map.of(),new ObjectMapper())).get();
    assertFalse(missing.configured());
    assertTrue(missing.missingEnvironmentVariables().contains("VOLC_ARK_API_KEY"));
    var controller=new com.xzwl.cloud.media.interfaces.http.ProviderStatusController(()->status);
    var req=new org.springframework.mock.web.MockHttpServletRequest();req.setUserPrincipal(()->"admin");req.addUserRole("ADMIN");
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.get(req));
    req.addUserRole("PRIMARY_ADMIN");assertEquals(status,controller.get(req));
  }
  @Test void productionAdapterIsWiredAndMissingEnvironmentCannotChargeOrSubmit() {
    assertInstanceOf(ProductionProviderPort.class,provider);
    var route=new RouteSnapshot("task","model","ark","Ark","https://ark.example","model","{}","{}",Instant.EPOCH);
    assertThrows(DependencyUnavailableException.class,()->provider.requireAvailable(route));
    assertEquals(0,jdbc.queryForObject("select count(*) from media_provider_operation",Integer.class));
  }
  @Test void missingImageSizeConfigurationCannotCreateAnExecutionPlan() {
    var json=new ObjectMapper();
    var configured=ProviderSettings.from(Map.of("MEDIA_ARK_PROVIDER_ID","ark","MEDIA_ARK_BASE_URL","https://ark.example",
        "VOLC_ARK_API_KEY","fixture-only-key","MEDIA_ARK_MODELS",
        "[{\"model\":\"model\",\"type\":\"IMAGE\",\"resolutions\":[\"2K\"],\"aspectRatios\":[\"1:1\"]}]"),json);
    var http=new ProviderHttp(java.net.http.HttpClient.newHttpClient(),json,java.time.Duration.ofSeconds(1));
    var candidate=new ProductionProviderPort(configured,new ProviderJournal(jdbc,json),new ArkMediaAdapter(http),
        new TencentMpsAdapter(http,java.time.Clock.systemUTC()));
    var request=new com.xzwl.cloud.media.domain.task.TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,
        "model","prompt","1:1","2K",1,0,java.util.List.of());
    var route=new RouteSnapshot("missing-size","model","ark","Ark","https://ark.example","model","{}","{}",Instant.EPOCH);
    assertThrows(DefinitiveRejectionException.class,()->candidate.requireAvailable(request,route));
    assertEquals(0,jdbc.queryForObject("select count(*) from media_provider_operation",Integer.class));
  }
  @Test void productionStorageDecoratorForwardsOmniValidationBeforePricing() throws Exception {
    var json=new ObjectMapper();
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    var received=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    server.createContext("/internal/v1/files/reference-media/access-url",exchange->{
      var body=json.readTree(exchange.getRequestBody()); received.set(body);
      boolean omni=body.path("omni").asBoolean();
      byte[] response=(omni?"{\"message\":\"reference image metadata unavailable or outside allowed limits; upload again\"}"
          :"{\"url\":\"https://storage.example/image\",\"mimeType\":\"image/png\"}").getBytes();
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(omni?400:200,response.length);exchange.getResponseBody().write(response);exchange.close();
    });server.start();
    try {
      var storage=new MediaConfiguration().mediaStoragePort(null,null,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
          "fixture-token",java.time.Duration.ofSeconds(2),java.time.Duration.ofSeconds(2));
      var refs=java.util.List.of(new com.xzwl.cloud.media.domain.task.MediaReference("old-image",null));
      var failure=assertThrows(DefinitiveRejectionException.class,()->storage.resolveReferences("owner",refs,
          com.xzwl.cloud.media.domain.task.ReferenceMode.OMNI));
      assertEquals("REFERENCE_METADATA_REQUIRED",failure.getMessage());
      assertTrue(received.get().path("omni").asBoolean());
      assertEquals("owner",received.get().path("ownerId").asText());
      assertEquals("https://storage.example/image",storage.resolveReferences("owner",refs).getFirst().httpsUrl());
      assertFalse(received.get().path("omni").asBoolean());
    } finally {server.stop(0);}
  }

}
