package com.xzwl.cloud.routing.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.routing.application.model.ConfigureModelHandler;
import com.xzwl.cloud.routing.application.model.ModelConfigurationStore;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderHandler;
import com.xzwl.cloud.routing.application.provider.ProviderConfigurationStore;
import com.xzwl.cloud.routing.application.route.RouteSnapshotStore;
import com.xzwl.cloud.routing.application.route.RouteTaskHandler;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoutingControllerTest {
  private final ObjectMapper json = new ObjectMapper();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    MemoryRepository repository = new MemoryRepository();
    mvc = MockMvcBuilders.standaloneSetup(new RoutingController(
        new ConfigureProviderHandler(repository),
        new ConfigureModelHandler(repository, repository),
        new RouteTaskHandler(repository, repository, repository,
            Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneOffset.UTC)),
        repository, repository), new RoutingAdminController(repository,repository,new ConfigureProviderHandler(repository),
        new ConfigureModelHandler(repository,repository),json)).setControllerAdvice(new com.xzwl.cloud.web.GlobalExceptionHandler()).build();
  }

  @Test void modelPolicyRoundTripsAndDisabledModelsBlockNewRoutesOnly() throws Exception {
    mvc.perform(primary(put("/api/v1/admin/routing/providers/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true}")));
    String body="""
        {"providerId":"ark","providerModel":"doubao-seedance-2-0-260128","enabled":true,"timeoutSeconds":45,"retryCount":2,
         "capability":{"type":"VIDEO","displayName":"Video","resolutions":["720p"],"aspectRatios":["16:9"],"maxCount":1,"minDurationSeconds":4,"maxDurationSeconds":15,"sizes":null}}
        """;
    var saved=mvc.perform(primary(put("/api/v1/admin/routing/models/video").contentType("application/json").content(body))).andReturn().getResponse();
    assertEquals(200,saved.getStatus());
    assertEquals(45,json.readTree(saved.getContentAsString()).path("timeoutSeconds").asInt());
    var route=mvc.perform(admin(post("/internal/v1/tasks/original/route").contentType("application/json").content("{\"requestedModel\":\"video\"}"))).andReturn().getResponse();
    assertEquals(201,route.getStatus());
    assertEquals(2,json.readTree(json.readTree(route.getContentAsString()).path("modelConfigurationJson").asText()).path("requestPolicy").path("retryCount").asInt());
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/models/video").contentType("application/json").content(body.replace("\"enabled\":true","\"enabled\":false")))).andReturn().getResponse().getStatus());
    assertEquals(0,json.readTree(mvc.perform(admin(get("/internal/v1/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(1,json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(422,mvc.perform(admin(post("/internal/v1/tasks/new-task/route").contentType("application/json").content("{\"requestedModel\":\"video\"}"))).andReturn().getResponse().getStatus());
    assertEquals(201,mvc.perform(admin(post("/internal/v1/tasks/original/route").contentType("application/json").content("{\"requestedModel\":\"video\"}"))).andReturn().getResponse().getStatus());
    for(String invalid:new String[]{body.replace("45","181"),body.replace("45","0"),body.replace("\"retryCount\":2","\"retryCount\":4"),body.replace("\"retryCount\":2","\"retryCount\":-1")})
      assertEquals(400,mvc.perform(primary(put("/api/v1/admin/routing/models/video").contentType("application/json").content(invalid))).andReturn().getResponse().getStatus());
    var inherited=mvc.perform(primary(put("/api/v1/admin/routing/models/video").contentType("application/json").content(body.replace("45","null").replace("\"retryCount\":2","\"retryCount\":null")))).andReturn().getResponse();
    assertEquals(200,inherited.getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(json.readTree(inherited.getContentAsString()).path("timeoutSeconds").isNull());
  }

  @Test void acceptsSixDocumentedRatiosIncludingUltrawide() throws Exception {
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/providers/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true}"))).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/models/wide").contentType("application/json").content("""
      {"providerId":"ark","providerModel":"doubao-seedance-2-0-260128","capability":{"type":"VIDEO","displayName":"Wide",
      "resolutions":["720p"],"aspectRatios":["1:1","16:9","9:16","4:3","3:4","21:9"],"maxCount":1,"minDurationSeconds":4,"maxDurationSeconds":15,"sizes":null}}
      """))).andReturn().getResponse().getStatus());
  }
  @Test void acceptsHttpProviderPortAndPrefixThroughBothConfigurationEntries() throws Exception {
    String endpoint = "http://14.103.26.228:3000/doubao/api/v3";
    String body = json.writeValueAsString(Map.of("displayName", "豆包中转", "endpoint", endpoint, "enabled", true, "configuration", Map.of()));
    assertEquals(200, mvc.perform(primary(put("/api/v1/admin/routing/providers/doubao-proxy")
        .contentType("application/json").content(body))).andReturn().getResponse().getStatus());
    assertEquals(endpoint, json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/providers")))
        .andReturn().getResponse().getContentAsString()).get(0).path("endpoint").asText());
    assertEquals(200, mvc.perform(admin(put("/internal/v1/providers/doubao-proxy")
        .contentType("application/json").content(body))).andReturn().getResponse().getStatus());
    for (String invalid : new String[]{"ftp://example.com", "http://user:pass@example.com", "http://example.com?secret=x", "http://example.com#x", "http://example.com:0", "http://example.com:65536"}) {
      assertEquals(400, mvc.perform(primary(put("/api/v1/admin/routing/providers/doubao-proxy")
          .contentType("application/json").content(json.writeValueAsString(Map.of("displayName", "Proxy", "endpoint", invalid, "enabled", true)))))
          .andReturn().getResponse().getStatus(), invalid);
    }
  }
  @Test void publicAdministrationRequiresPrimaryAdministrator() throws Exception {
    String path="/api/v1/admin/routing/providers";
    assertEquals(401,mvc.perform(get(path).header("X-Actor-Role","ADMIN")).andReturn().getResponse().getStatus());
    for(String role:new String[]{"NORMAL_ADMIN","ADMIN"}) {
      assertEquals(403,mvc.perform(get(path).with(r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(put(path+"/ark").contentType("application/json")
          .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true}")
          .with(r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
    }
    assertEquals(200,mvc.perform(primary(put(path+"/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true,\"apiKey\":\"never-persist-this\"}"))).andReturn().getResponse().getStatus());
    var providers=mvc.perform(primary(get(path))).andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertFalse(providers.contains("never-persist-this"));
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/models/image-model").contentType("application/json").content("""
      {"providerId":"ark","providerModel":"upstream-model","capability":{"type":"IMAGE","displayName":"Image",
      "resolutions":["2k"],"aspectRatios":["1:1"],"maxCount":4,"minDurationSeconds":0,"maxDurationSeconds":0,"sizes":{"2k":{"1:1":"2048x2048"}}}}
      """))).andReturn().getResponse().getStatus());
    assertEquals(1,json.readTree(mvc.perform(service(get("/internal/v1/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(200,mvc.perform(primary(put(path+"/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":false}"))).andReturn().getResponse().getStatus());
    assertEquals(0,json.readTree(mvc.perform(service(get("/internal/v1/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(422,mvc.perform(service(post("/internal/v1/tasks/disabled-task/route").contentType("application/json")
        .content("{\"requestedModel\":\"image-model\"}"))).andReturn().getResponse().getStatus());
  }
  @Test void deletesOnlyModelConfigurationAndRequiresPrimaryAdministrator() throws Exception {
    mvc.perform(primary(put("/api/v1/admin/routing/providers/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true}")));
    assertEquals(200, mvc.perform(admin(put("/internal/v1/models/deletable-model").contentType("application/json")
        .content("{\"providerId\":\"ark\",\"providerModel\":\"real-model\",\"configuration\":{}}"))).andReturn().getResponse().getStatus());
    assertEquals(201, mvc.perform(service(post("/internal/v1/tasks/existing-task/route").contentType("application/json")
        .content("{\"requestedModel\":\"deletable-model\"}"))).andReturn().getResponse().getStatus());
    String path = "/api/v1/admin/routing/models/deletable-model";
    assertEquals(401, mvc.perform(delete(path)).andReturn().getResponse().getStatus());
    for (String role : new String[]{"NORMAL_ADMIN", "ADMIN"})
      assertEquals(403, mvc.perform(delete(path).with(r -> { r.setUserPrincipal(() -> "actor"); r.addUserRole(role); return r; })).andReturn().getResponse().getStatus());
    assertEquals(1, json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(204, mvc.perform(primary(delete(path))).andReturn().getResponse().getStatus());
    assertEquals(204, mvc.perform(primary(delete(path))).andReturn().getResponse().getStatus());
    assertEquals(0, json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(0, json.readTree(mvc.perform(service(get("/internal/v1/models"))).andReturn().getResponse().getContentAsString()).size());
    assertEquals(200, mvc.perform(service(get("/internal/v1/tasks/existing-task/route"))).andReturn().getResponse().getStatus());
    assertEquals(422, mvc.perform(service(post("/internal/v1/tasks/new-task/route").contentType("application/json")
        .content("{\"requestedModel\":\"deletable-model\"}"))).andReturn().getResponse().getStatus());
    assertEquals(1, json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/providers"))).andReturn().getResponse().getContentAsString()).size());
  }
  @Test void nativeAsyncImageCapabilitiesDoNotInventPixelSizes() throws Exception {
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/providers/zex").contentType("application/json")
        .content("{\"displayName\":\"ZEX\",\"endpoint\":\"https://zexapi.com/v1\",\"enabled\":true}"))).andReturn().getResponse().getStatus());
    String body="""
      {"providerId":"zex","providerModel":"gpt-image-2-2K","capability":{"type":"IMAGE","displayName":"Gemini",
      "resolutions":["2K"],"aspectRatios":["1:1"],"maxCount":1,"minDurationSeconds":0,"maxDurationSeconds":0,"sizes":null}}
      """;
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/models/gemini-image").contentType("application/json").content(body))).andReturn().getResponse().getStatus());
    var stored=json.readTree(mvc.perform(primary(get("/api/v1/admin/routing/models"))).andReturn().getResponse().getContentAsString()).get(0);
    assertEquals("zex",stored.path("providerId").asText());
    org.junit.jupiter.api.Assertions.assertTrue(stored.path("capability").path("sizes").isNull());
  }
  @Test void rejectsIncompleteAndDuplicateAdvertisedImageCapabilities() throws Exception {
    assertEquals(200,mvc.perform(primary(put("/api/v1/admin/routing/providers/ark").contentType("application/json")
        .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://ark.example\",\"enabled\":true}"))).andReturn().getResponse().getStatus());
    var base=json.readTree("""
      {"providerId":"ark","providerModel":"upstream","capability":{"type":"IMAGE","displayName":"Image",
      "resolutions":["2k"],"aspectRatios":["1:1"],"maxCount":1,"minDurationSeconds":0,"maxDurationSeconds":0,"sizes":{"2k":{"1:1":"2048x2048"}}}}
      """);
    for(String mode:new String[]{"missing-sizes","missing-cell","duplicate-resolution","duplicate-ratio"}) {
      var body=base.deepCopy();var capability=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("capability");
      switch(mode) {
        case "missing-sizes" -> capability.remove("sizes");
        case "missing-cell" -> capability.putObject("sizes");
        case "duplicate-resolution" -> ((com.fasterxml.jackson.databind.node.ArrayNode)capability.path("resolutions")).add("2k");
        case "duplicate-ratio" -> ((com.fasterxml.jackson.databind.node.ArrayNode)capability.path("aspectRatios")).add("1:1");
      }
      assertEquals(400,mvc.perform(primary(put("/api/v1/admin/routing/models/m").contentType("application/json").content(body.toString()))).andReturn().getResponse().getStatus(),mode);
    }
  }
  @Test void rejectedCredentialInputDoesNotReachResponsesOrLogs() throws Exception {
    var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();root.addAppender(events);
    try {
      var response=mvc.perform(primary(put("/api/v1/admin/routing/providers/ark").contentType("application/json")
          .content("{\"displayName\":\"Ark\",\"endpoint\":\"https://name:private-fixture-marker@ark.example\",\"enabled\":true}")))
          .andReturn().getResponse();
      assertEquals(400,response.getStatus());
      org.junit.jupiter.api.Assertions.assertFalse(response.getContentAsString().contains("private-fixture-marker"));
      for(var event:events.list) {
        org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("private-fixture-marker"));
        if(event.getThrowableProxy()!=null) org.junit.jupiter.api.Assertions.assertFalse(
            ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()).contains("private-fixture-marker"));
      }
    } finally {root.detachAppender(events);events.stop();}
  }
  private static MockHttpServletRequestBuilder primary(MockHttpServletRequestBuilder r) {
    return admin(r).with(servlet->{servlet.addUserRole("PRIMARY_ADMIN");return servlet;});
  }

  @Test
  void configuresAndRoutesThroughTheNewInternalApi() throws Exception {
    assertEquals(200, mvc.perform(admin(put("/internal/v1/providers/volcano")
        .contentType("application/json")
        .content("""
            {"displayName":"火山引擎","endpoint":"https://ark.example","enabled":true,
             "configuration":{"timeoutSeconds":120}}
            """))).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(admin(put("/internal/v1/models/gpt-image-2")
        .contentType("application/json")
        .content("""
            {"providerId":"volcano","providerModel":"doubao-seedream",
             "configuration":{"quality":"hd"}}
            """))).andReturn().getResponse().getStatus());

    var routed = mvc.perform(service(post("/internal/v1/tasks/task-1/route")
        .contentType("application/json")
        .content("{\"requestedModel\":\"gpt-image-2\"}")))
        .andReturn().getResponse();
    assertEquals(201, routed.getStatus());
    assertEquals("volcano", json.readTree(routed.getContentAsString()).path("providerId").textValue());

    var loaded = mvc.perform(service(get("/internal/v1/tasks/task-1/route")))
        .andReturn().getResponse();
    assertEquals(200, loaded.getStatus());
    assertEquals("doubao-seedream",
        json.readTree(loaded.getContentAsString()).path("providerModel").textValue());

    assertEquals(200, mvc.perform(admin(put("/internal/v1/models/another-model")
        .contentType("application/json")
        .content("""
            {"providerId":"volcano","providerModel":"another-provider-model",
             "configuration":{}}
            """)))
        .andReturn().getResponse().getStatus());
    assertEquals(409, mvc.perform(service(post("/internal/v1/tasks/task-1/route")
        .contentType("application/json")
        .content("{\"requestedModel\":\"another-model\"}")))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void rejectsSecretsAndDataUrlsInPersistedConfiguration() throws Exception {
    for (String configuration : new String[] {
        "{\"secretKey\":\"not-allowed\"}",
        "{\"secretAccessKey\":\"not-allowed\"}",
        "{\"secret\":\"not-allowed\"}",
        "{\"privateKey\":\"not-allowed\"}",
        "{\"signingKey\":\"not-allowed\"}",
        "{\"xApiKey\":\"not-allowed\"}",
        "{\"authToken\":\"not-allowed\"}",
        "{\"bearerToken\":\"not-allowed\"}",
        "{\"oauthToken\":\"not-allowed\"}",
        "{\"clientCredential\":\"not-allowed\"}",
        "{\"apiKey\":\"not-allowed\"}",
        "{\"preview\":\"data:image/png;base64,AAAA\"}"}) {
      int status = mvc.perform(admin(put("/internal/v1/providers/provider")
          .contentType("application/json")
          .content("{\"displayName\":\"Provider\",\"endpoint\":\"https://example.com\","
              + "\"enabled\":true,\"configuration\":" + configuration + "}")))
          .andReturn().getResponse().getStatus();
      assertEquals(400, status);
    }

    assertEquals(400, mvc.perform(admin(put("/internal/v1/providers/provider")
        .contentType("application/json")
        .content("""
            {"displayName":"Provider","endpoint":"https://user:password@example.com",
             "enabled":true,"configuration":{}}
            """)))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void internalEndpointsFailClosedWithoutAuthenticatedServletIdentity() throws Exception {
    assertEquals(401, mvc.perform(put("/internal/v1/providers/provider")
        .contentType("application/json")
        .content("""
            {"displayName":"Provider","endpoint":"https://example.com",
             "enabled":true,"configuration":{}}
            """))
        .andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(put("/internal/v1/providers/provider")
        .contentType("application/json")
        .content("""
            {"displayName":"Provider","endpoint":"https://example.com",
             "enabled":true,"configuration":{}}
            """)
        .principal(() -> "ordinary-user"))
        .andReturn().getResponse().getStatus());
    assertEquals(401, mvc.perform(post("/internal/v1/tasks/task/route")
        .contentType("application/json")
        .content("{\"requestedModel\":\"model\"}"))
        .andReturn().getResponse().getStatus());
  }

  @Test void listsModelsInOrderOnlyWithMediaToken() throws Exception {
    configuresAndRoutesThroughTheNewInternalApi();
    var response = mvc.perform(service(get("/internal/v1/models"))).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    var list = json.readTree(response.getContentAsString());
    assertEquals("another-model", list.get(0).path("modelId").asText());
    assertEquals("gpt-image-2", list.get(1).path("modelId").asText());
    assertEquals("doubao-seedream", list.get(1).path("providerModel").asText());
    assertEquals("{\"quality\":\"hd\"}", list.get(1).path("configurationJson").asText());
    assertEquals(401, mvc.perform(get("/internal/v1/models")).andReturn().getResponse().getStatus());
    var filter = new RoutingMediaFilter("media-token-012345678901234567890123");
    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/internal/v1/models");
    var denied = new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(request, denied, (req,res) -> { throw new AssertionError("missing token admitted"); });
    assertEquals(401, denied.getStatus());
    request.addHeader("X-Service-Token", "media-token-012345678901234567890123");
    filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req,res) -> {
      var authenticated = (jakarta.servlet.http.HttpServletRequest) req;
      assertEquals("media-service", authenticated.getUserPrincipal().getName());
      assertEquals(false, authenticated.isUserInRole("ADMIN"));
    });
  }

  private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request.with(servlet -> {
      servlet.setUserPrincipal(() -> "admin-1");
      servlet.addUserRole("ADMIN");
      return servlet;
    });
  }

  private static MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder request) {
    return request.principal(() -> "media-service");
  }

  private static final class MemoryRepository
      implements ProviderConfigurationStore, ModelConfigurationStore, RouteSnapshotStore {
    private final Map<String, Provider> providers = new HashMap<>();
    private final Map<String, ModelConfiguration> models = new HashMap<>();
    private final Map<String, RouteSnapshot> snapshots = new HashMap<>();

    public java.util.List<Provider> findProviders() { return providers.values().stream().toList(); }
    public void save(Provider provider) { providers.put(provider.id(), provider); }
    public Optional<Provider> findById(String id) { return Optional.ofNullable(providers.get(id)); }
    public java.util.List<ModelConfiguration> findAll() {
      return models.values().stream().sorted(java.util.Comparator.comparing(ModelConfiguration::modelId)).toList();
    }
    public void save(ModelConfiguration model) { models.put(model.modelId(), model); }
    public void deleteByModelId(String id) { models.remove(id); }
    public Optional<ModelConfiguration> findByModelId(String id) {
      return Optional.ofNullable(models.get(id));
    }
    public boolean saveIfAbsent(RouteSnapshot snapshot) {
      return snapshots.putIfAbsent(snapshot.taskId(), snapshot) == null;
    }
    public Optional<RouteSnapshot> findByTaskId(String id) {
      return Optional.ofNullable(snapshots.get(id));
    }
  }
}
