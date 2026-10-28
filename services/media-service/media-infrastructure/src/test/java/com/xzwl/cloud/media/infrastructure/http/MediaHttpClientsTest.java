package com.xzwl.cloud.media.infrastructure.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MediaHttpClientsTest {
  final RestClient.Builder builder=RestClient.builder().baseUrl("https://internal.example")
      .defaultHeader("X-Service-Token","test-token");
  final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
  final RestClient client=builder.build();
  @Test void routingUsesExistingRouteEndpointAndPreservesTheSnapshot() {
    server.expect(requestTo("https://internal.example/internal/v1/tasks/task-1/route"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Service-Token","test-token"))
        .andExpect(content().json("{\"requestedModel\":\"model\"}"))
        .andRespond(withSuccess(route("{}"),MediaType.APPLICATION_JSON));
    var snapshot=new HttpRoutingPort(client,new ObjectMapper()).route("task-1","model");
    assertEquals("provider-model",snapshot.providerModel());
    assertEquals("{}",snapshot.modelConfigurationJson()); server.verify();
  }
  @Test void storageCallsExistingInternalArchiveAndPersistsFileIdOnly() {
    server.expect(requestTo("https://internal.example/internal/v1/files/media-results/archive-url"))
        .andExpect(content().json("{\"ownerId\":\"owner\",\"sourceUrl\":\"https://cdn.example/output.png\",\"archiveKey\":\"task-1:RESULT:0\"}"))
        .andRespond(withSuccess("{\"fileId\":\"file-1\",\"mimeType\":\"image/png\",\"sizeBytes\":24,\"purpose\":\"MEDIA_RESULT\",\"createdAt\":\"2026-09-07T00:00:00Z\"}",MediaType.APPLICATION_JSON));
    assertEquals("file-1",new HttpStoragePort(client).archive("owner","task-1",0,"https://cdn.example/output.png")); server.verify();
  }
  @Test void invalidArchiveIdentityCannotSendARequest() {
    var storage=new HttpStoragePort(client);
    for(String task : java.util.List.of("", "task:RESULT", "x".repeat(129)))
      assertThrows(IllegalArgumentException.class,()->storage.archive("owner",task,0,"https://cdn.example/a.mp4"));
    assertThrows(IllegalArgumentException.class,()->storage.archive("owner","task",-1,"https://cdn.example/a.mp4"));
    server.verify();
  }
  @Test void base64NeverReachesStorageAndSecretsCannotEnterRouteSnapshots() {
    assertThrows(IllegalArgumentException.class,() -> new HttpStoragePort(client).archive("owner","task",0,"data:image/png;base64,AAAA"));
    server.expect(requestTo("https://internal.example/internal/v1/tasks/task-1/route"))
        .andRespond(withSuccess(route("{\\\"apiKey\\\":\\\"secret\\\"}"),MediaType.APPLICATION_JSON));
    assertThrows(DefinitiveRejectionException.class,() -> new HttpRoutingPort(client,new ObjectMapper()).route("task-1","model")); server.verify();
  }
  @Test void providerConfigurationErrorIsNotMistakenForACompletedTask() {
    server.expect(requestTo("https://internal.example/internal/v1/tasks/task-1/route"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThrows(DefinitiveRejectionException.class,() -> new HttpRoutingPort(client,new ObjectMapper()).route("task-1","model")); server.verify();
  }
  @Test void timeoutAndServerErrorsRemainRetryable() {
    server.expect(requestTo("https://internal.example/internal/v1/tasks/task-1/route"))
        .andRespond(withServerError());
    assertThrows(DependencyUnavailableException.class,() -> new HttpRoutingPort(client,new ObjectMapper()).route("task-1","model")); server.verify();
  }
  @Test void resolvesOwnedFileIdsAndPreservesExistingHttpsReferences() {
    server.expect(requestTo("https://internal.example/internal/v1/files/reference-media/access-url"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Service-Token","test-token"))
        .andExpect(content().json("{\"ownerId\":\"owner\",\"fileId\":\"file-1\"}"))
        .andRespond(withSuccess("{\"url\":\"https://storage.example/reference\",\"mimeType\":\"image/png\",\"expiresAt\":\"2026-09-08T10:00:00Z\"}",MediaType.APPLICATION_JSON));
    var direct = new com.xzwl.cloud.media.domain.task.MediaReference(null,"https://cdn.example/reference");
    var resolved = new HttpStoragePort(client).resolveReferences("owner", java.util.List.of(
        new com.xzwl.cloud.media.domain.task.MediaReference("file-1",null), direct));
    assertEquals("https://storage.example/reference",resolved.getFirst().httpsUrl());
    assertSame(direct,resolved.get(1)); server.verify();
  }
  @Test void referenceOwnerDenialAndNonHttpsResponsesAreDefinitive() {
    server.expect(requestTo("https://internal.example/internal/v1/files/reference-media/access-url"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    server.expect(requestTo("https://internal.example/internal/v1/files/reference-media/access-url"))
        .andRespond(withSuccess("{\"url\":\"http://storage.example/reference\"}",MediaType.APPLICATION_JSON));
    var references=java.util.List.of(new com.xzwl.cloud.media.domain.task.MediaReference("file-1",null));
    assertThrows(DefinitiveRejectionException.class, () -> new HttpStoragePort(client).resolveReferences("owner",references));
    assertThrows(DefinitiveRejectionException.class, () -> new HttpStoragePort(client).resolveReferences("owner",references));
    server.verify();
  }
  @Test void canonicalMetadataReplacesClientDurationAndRejectsMistypedFiles() {
    for(int i=0;i<2;i++) server.expect(requestTo("https://internal.example/internal/v1/files/reference-media/access-url"))
        .andRespond(withSuccess("{\"url\":\"https://storage.example/video\",\"mimeType\":\"video/mp4\",\"durationSeconds\":18.5}",MediaType.APPLICATION_JSON));
    var video=new com.xzwl.cloud.media.domain.task.MediaReference("file-video",null,
        com.xzwl.cloud.media.domain.task.MediaReference.Kind.VIDEO,3.0);
    var resolved=new HttpStoragePort(client).resolveReferences("owner",java.util.List.of(video));
    assertEquals(18.5,resolved.getFirst().durationSeconds());
    assertEquals(video.kind(),resolved.getFirst().kind());
    assertThrows(DefinitiveRejectionException.class,()->new HttpStoragePort(client).resolveReferences("owner",
        java.util.List.of(new com.xzwl.cloud.media.domain.task.MediaReference("file-video",null))));
    server.verify();
  }
  @Test void omniRequiresValidatedStorageMetadataAndExplainsLegacyFiles() {
    server.expect(requestTo("https://internal.example/internal/v1/files/reference-media/access-url"))
        .andExpect(content().json("{\"ownerId\":\"owner\",\"fileId\":\"image\",\"omni\":true}",true))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
            .body("{\"message\":\"reference image metadata is missing; upload the file again\"}"));
    var failure=assertThrows(DefinitiveRejectionException.class,()->new HttpStoragePort(client).resolveReferences("owner",
        java.util.List.of(new com.xzwl.cloud.media.domain.task.MediaReference("image",null)),
        com.xzwl.cloud.media.domain.task.ReferenceMode.OMNI));
    assertEquals("REFERENCE_METADATA_REQUIRED",failure.getMessage()); server.verify();
  }
  @Test void catalogAndQuoteUseOnlyInternalOwnerScopedEndpoints() {
    server.expect(requestTo("https://internal.example/internal/v1/models"))
        .andExpect(method(HttpMethod.GET)).andExpect(header("X-Service-Token","test-token"))
        .andRespond(withSuccess("[{\"modelId\":\"image\",\"providerId\":\"ark\",\"providerModel\":\"upstream\",\"configurationJson\":\"{\\\"type\\\":\\\"IMAGE\\\"}\"}]",MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://internal.example/internal/v1/billing/media-prices?ownerId=owner"))
        .andExpect(method(HttpMethod.GET)).andExpect(header("X-Service-Token","test-token"))
        .andRespond(withSuccess("[{\"model\":\"image\",\"resolution\":\"2K\",\"amountPerUnit\":0}]",MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://internal.example/internal/v1/billing/media-quotes"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Service-Token","test-token"))
        .andExpect(content().json("{\"ownerId\":\"owner\",\"type\":\"IMAGE\",\"model\":\"image\",\"resolution\":\"2K\",\"count\":3,\"durationSeconds\":0}",true))
        .andRespond(withSuccess("{\"amount\":1.2340,\"rate\":10,\"points\":12.3400}",MediaType.APPLICATION_JSON));
    var models=new HttpRoutingPort(client,new ObjectMapper()).models();
    assertEquals("IMAGE",models.getFirst().configuration().get("type"));
    var billing=new HttpBillingPort(client);
    assertEquals(0,billing.availablePrices("owner").getFirst().amountPerUnit().signum());
    var request=new com.xzwl.cloud.media.domain.task.TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,
        "image","prompt","16:9","2K",3,0,java.util.List.of());
    assertEquals(new java.math.BigDecimal("12.3400"),billing.quote("owner",request));
    server.verify();
  }
  private String route(String configuration) {
    return "{\"taskId\":\"task-1\",\"requestedModel\":\"model\",\"providerId\":\"provider\",\"providerDisplayName\":\"Provider\",\"providerEndpoint\":\"https://provider.example\",\"providerModel\":\"provider-model\",\"providerConfigurationJson\":\""+configuration+"\",\"modelConfigurationJson\":\"{}\",\"selectedAt\":\"2026-09-07T00:00:00Z\"}";
  }
}
