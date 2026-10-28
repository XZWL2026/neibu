package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.net.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.mybatis.spring.annotation.MapperScan;
import com.xzwl.cloud.media.infrastructure.task.MyBatisMediaTaskStore;
import com.xzwl.cloud.media.infrastructure.http.HttpStoragePort;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore.Connection;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductionProviderTest {
  @TempDir Path directory;
  HttpServer server;
  final ObjectMapper json=new ObjectMapper();
  final List<JsonNode> bodies=new CopyOnWriteArrayList<>();
  final List<Map<String,List<String>>> headers=new CopyOnWriteArrayList<>();
  final List<String> paths=new CopyOnWriteArrayList<>();
  final AtomicInteger posts=new AtomicInteger();
  final Map<String,String> actionAnswers=new ConcurrentHashMap<>();
  volatile int status=200;
  volatile String answer="{}";
  volatile long delay;
  String endpoint;
  ProviderSettings settings;
  ProviderJournal journal;
  JdbcTemplate jdbc;
  Clock clock=Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"),ZoneOffset.UTC);
  @BeforeEach void open() throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/",e->{
      headers.add(Map.copyOf(e.getRequestHeaders()));
      paths.add(e.getRequestMethod()+" "+e.getRequestURI().getPath());
      if(e.getRequestMethod().equals("POST")) {
        posts.incrementAndGet(); bodies.add(json.readTree(e.getRequestBody()));
      }
      try { if(delay>0) Thread.sleep(delay); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      try {
        String response=e.getRequestURI().getPath().equals("/internal/v1/files/media-results/archive-url")
            ? "{\"fileId\":\"archived-file\",\"purpose\":\"MEDIA_RESULT\",\"sizeBytes\":4096}" : actionAnswers.getOrDefault(e.getRequestHeaders().getFirst("X-TC-Action") == null ? "" : e.getRequestHeaders().getFirst("X-TC-Action"),answer);
        byte[] out=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json");
        e.getResponseHeaders().set("X-Request-Id","fixture-request-id");
        e.getResponseHeaders().set("X-Tt-Logid","fixture-log-id");
        e.getResponseHeaders().set("Set-Cookie","private-cookie=do-not-record");
        if(status==302) e.getResponseHeaders().set("Location",endpoint+"/redirect-target");
        e.sendResponseHeaders(status,out.length); e.getResponseBody().write(out);
      } finally { e.close(); }
    });
    server.start(); endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    settings=ProviderSettings.from(Map.ofEntries(
        Map.entry("MEDIA_ARK_BASE_URL",endpoint),Map.entry("MEDIA_ARK_PROVIDER_ID","ark"),
        Map.entry("VOLC_ARK_API_KEY","fixture-ark-secret"),
        Map.entry("MEDIA_ARK_MODELS", """
            [{"model":"seedream-fixture","type":"IMAGE","resolutions":["2K","4K"],
              "aspectRatios":["1:1","16:9"],"sizes":{"2K":{"1:1":"2048x2048","16:9":"2560x1440"},"4K":{"1:1":"4096x4096"}}},
             {"model":"seedance-fixture","type":"VIDEO","resolutions":["480p","720p","1080p","4K"],
              "aspectRatios":["16:9"],"minDuration":4,"maxDuration":15}]
            """),
        Map.entry("MEDIA_MPS_ENDPOINT",endpoint),Map.entry("TENCENTCLOUD_SECRET_ID","fixture-id"),
        Map.entry("TENCENTCLOUD_SECRET_KEY","fixture-tencent-secret"),Map.entry("MEDIA_MPS_REGION","ap-guangzhou"),
        Map.entry("MEDIA_MPS_BUCKET","fixture-123"),Map.entry("MEDIA_MPS_BUCKET_REGION","ap-guangzhou"),
        Map.entry("MEDIA_MPS_TEMPLATE_720P","720"),Map.entry("MEDIA_MPS_TEMPLATE_1080P","1080"),
        Map.entry("MEDIA_MPS_TEMPLATE_4K","2160")),json);
    jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V3__media_provider_operations.sql")) {
      jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V6__media_provider_failure_detail.sql")) {
      for(String sql:new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))
        if(!sql.isBlank()) jdbc.execute(sql);
    }
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V9__media_generation_diagnostics.sql")) {
      for(String sql:new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))
        if(!sql.isBlank()) jdbc.execute(sql);
    }
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V17__media_enhancement_steps.sql")) {
      jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    journal=new ProviderJournal(jdbc,json);
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V21__media_provider_http_diagnostics.sql")) {
      for(String sql:new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))
        if(!sql.isBlank()) jdbc.execute(sql);
    }
  }
  @AfterEach void close() { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
  ProductionProviderPort adapter() { return adapter(Duration.ofSeconds(2)); }
  ProductionProviderPort adapter(Duration read) {
    var http=new ProviderHttp(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(),json,read);
    return new ProductionProviderPort(settings,journal,new ArkMediaAdapter(http),new TencentMpsAdapter(http,clock));
  }
  TaskRequest request(MediaType type,int count,String resolution) {
    return new TaskRequest(type,"public-model","a cloud",resolution,count,type==MediaType.IMAGE?0:5,List.of());
  }
  RouteSnapshot route(String id,MediaType type) {
    return new RouteSnapshot(id,"public-model","ark","Ark","https://ark.example",type==MediaType.IMAGE?"seedream-fixture":"seedance-fixture","{}","{}",clock.instant());
  }
  String submit(ProductionProviderPort p,String id,TaskRequest r) { return p.submit(id,"owner",r,route(id,r.type())); }

  @Test void rejectedSubmissionRetainsHttpEvidenceWithoutLeakingRequestSecrets() throws Exception {
    status=400;
    answer="""
        {"error":{"code":"invalid_request","message":"reference image 6: unsupported format; fixture-ark-secret",
        "param":"content[6].image_url","type":"invalid_request_error"},"request_id":"upstream-request-123"}
        """;
    var provider=adapter();
    var references=java.util.stream.IntStream.range(0,6).mapToObj(i->
        new MediaReference(null,"https://cdn.example/reference-"+i+".png?signature=private-signature")).toList();
    var request=new TaskRequest(MediaType.VIDEO,"public-model","测".repeat(4116),"16:9","720p",1,15,references,ReferenceMode.OMNI);
    var route=new RouteSnapshot("diagnostic-rejection","public-model","ark","Ark","https://ark.example",
        "doubao-seedance-2-0-260128","{}","{\"type\":\"VIDEO\",\"resolutions\":[\"720p\"],\"aspectRatios\":[\"16:9\"],\"minDurationSeconds\":4,\"maxDurationSeconds\":15}",clock.instant());
    assertThrows(DefinitiveRejectionException.class,()->provider.submit(route.taskId(),"owner",request,route));
    var rows=jdbc.queryForList("SELECT detail_json FROM media_provider_http_diagnostic WHERE operation_id=? ORDER BY created_at","diagnostic-rejection");
    assertEquals(2,rows.size(),"persist request start and HTTP response even when submission is rejected");
    var detail=json.readTree((String)rows.getLast().get("detail_json"));
    assertEquals(400,detail.path("httpStatus").asInt());
    assertEquals("fixture-request-id",detail.path("responseHeaders").path("x-request-id").asText());
    assertEquals("fixture-log-id",detail.path("responseHeaders").path("x-tt-logid").asText());
    assertFalse(rows.toString().contains("private-cookie"));
    assertEquals("upstream-request-123",detail.path("response").path("request_id").asText());
    assertTrue(detail.path("response").path("error").path("message").asText().contains("unsupported format"));
    assertEquals("content[6].image_url",detail.path("response").path("error").path("param").asText());
    assertEquals("doubao-seedance-2-0-260128",detail.path("request").path("model").asText());
    assertEquals(4116,detail.path("promptCharacters").asInt());
    assertEquals(12348,detail.path("promptUtf8Bytes").asInt());
    assertEquals(6,detail.path("referenceCount").asInt());
    assertEquals("OMNI",detail.path("referenceMode").asText());
    assertEquals("720p",detail.path("requestedResolution").asText());
    assertEquals("480p",detail.path("request").path("resolution").asText());
    assertEquals(15,detail.path("request").path("duration").asInt());
    assertEquals(7,detail.path("request").path("content").size());
    assertFalse(rows.toString().contains("private-signature"));
    assertFalse(rows.toString().contains("fixture-ark-secret"));
    assertFalse(rows.toString().contains(request.prompt()));
    assertEquals("invalid_request",provider.failure("diagnostic-rejection").code());
    assertEquals("ARK_SENDING",provider.failure("diagnostic-rejection").stage());
    assertEquals(1,posts.get());
  }

  @Test void diagnosticCoversLogicalRejectionMalformedResponseAndUncertainHttpWithoutPostRetry() throws Exception {
    for(int code:List.of(200,400,429,500)) {
      for(String body:List.of("{\"error\":{\"code\":\"invalid_request\",\"message\":\"unsupported duration\"}}","<html>upstream unavailable</html>")) {
        status=code;answer=body;String id=UUID.randomUUID().toString();int before=posts.get();
        assertThrows(RuntimeException.class,()->submit(adapter(),id,request(MediaType.VIDEO,1,"720p")));
        var rows=jdbc.queryForList("SELECT detail_json FROM media_provider_http_diagnostic WHERE operation_id=?",String.class,id);
        assertEquals(2,rows.size());
        assertTrue(rows.stream().anyMatch(row->row.contains("response_received") && row.contains("\"httpStatus\":"+code)));
        assertEquals(before+1,posts.get());
      }
    }
  }

  @Test void diagnosticKeepsTransportFailureAndNeverResubmitsUncertainPost() throws Exception {
    delay=300;String id="diagnostic-timeout";var request=request(MediaType.VIDEO,1,"720p");
    var provider=adapter(Duration.ofMillis(100));
    assertThrows(DependencyUnavailableException.class,()->submit(provider,id,request));
    var rows=jdbc.queryForList("SELECT detail_json FROM media_provider_http_diagnostic WHERE operation_id=?",String.class,id);
    assertEquals(2,rows.size());
    assertTrue(rows.stream().anyMatch(row->row.contains("transport_failed") && row.contains("TimeoutException")));
    assertThrows(DependencyUnavailableException.class,()->submit(provider,id,request));
    assertEquals(1,posts.get());assertEquals("ARK_SENDING",journal.find(id).phase());
  }

  @Test void diagnosticRetainsTerminalPollingErrorAndSuppressesOrdinaryPendingPolls() throws Exception {
    String id="diagnostic-poll";var request=request(MediaType.VIDEO,1,"720p");var provider=adapter();
    answer="{\"id\":\"upstream-task\"}";submit(provider,id,request);
    answer="{\"id\":\"upstream-task\",\"status\":\"running\"}";
    assertEquals(ProviderPort.ProviderPoll.Status.PENDING,provider.poll(id,request,route(id,MediaType.VIDEO)).status());
    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_provider_http_diagnostic WHERE operation_id=?",Integer.class,id));
    answer="{\"id\":\"upstream-task\",\"status\":\"failed\",\"error\":{\"code\":\"BadInput\",\"message\":\"image decoding failed\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.FAILED,provider.poll(id,request,route(id,MediaType.VIDEO)).status());
    var rows=jdbc.queryForList("SELECT detail_json FROM media_provider_http_diagnostic WHERE operation_id=?",String.class,id);
    assertEquals(3,rows.size());assertTrue(rows.stream().anyMatch(row->row.contains("ARK_RUNNING") && row.contains("image decoding failed")));
  }

  @Test void diagnosticPersistenceFailureDoesNotChangeAcceptedSubmission() {
    jdbc.execute("DROP TABLE media_provider_http_diagnostic");answer="{\"id\":\"upstream-accepted\"}";
    var request=request(MediaType.VIDEO,1,"720p");var provider=adapter();
    assertEquals("diagnostic-db-down",submit(provider,"diagnostic-db-down",request));
    assertEquals("ARK_RUNNING",journal.find("diagnostic-db-down").phase());
    submit(provider,"diagnostic-db-down",request);assertEquals(1,posts.get());
  }


  private com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration policyConfiguration(boolean enabled) {
    var old=settings.mpsConfiguration();
    var template=new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.EnhancementTemplate("fullhd","1080p",List.of("720p"),enabled,"br",null,
        Map.of("WIDE",108307L,"SQUARE",108308L,"STANDARD",108309L,"ULTRAWIDE",108310L));
    return new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration(3,old.secretId(),old.secretKey(),old.region(),old.bucket(),old.bucketRegion(),old.outputDir(),old.template720p(),old.template1080p(),old.template4k(),
        List.of(template),List.of(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.BitrateProfile("br","Delivery",12000)));
  }
  @Test void localMpsPolicyFreezesTerminalBitrateActivitiesAndKeepsAcceptedWorkAfterDisable() throws Exception {
    scheduledSettings(scheduleEnvironment());settings=settings.withMpsConfiguration(policyConfiguration(true));
    scheduleMetadata(108307,1920,1080,false);
    var request=request(MediaType.VIDEO,1,"1080p");var route=scheduleRoute("bitrate-plan");
    adapter().requireAvailable(request,route);
    var plan=journal.executionPlan(route.taskId());
    assertEquals(3,plan.enhancement().configurationVersion());assertEquals(12000,plan.enhancement().videoBitrateKbps());
    assertEquals(180,plan.timeoutSeconds());assertEquals(0,plan.retryCount());
    assertEquals(List.of(10L,20L),plan.enhancement().definitions());
    settings=settings.withMpsConfiguration(policyConfiguration(false));
    int readOnlyPosts=posts.get();adapter().requireAvailable(request,route);assertEquals(readOnlyPosts,posts.get());
    assertThrows(DefinitiveRejectionException.class,()->adapter().requireAvailable(request,scheduleRoute("disabled-new")));
    assertNull(journal.find("disabled-new"));assertEquals(readOnlyPosts,posts.get());
    answer="{\"id\":\"native-receipt\"}";adapter().submit(route.taskId(),"owner",request,route);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";adapter().poll(route.taskId(),request,route);
    answer="{\"Response\":{\"TaskId\":\"schedule-receipt\"}}";adapter().poll(route.taskId(),request,route);
    JsonNode sent=bodies.getLast();assertFalse(sent.has("ScheduleId"));assertFalse(sent.has("MediaProcessTask"));
    var activities=sent.path("Activities");assertEquals(4,activities.size());
    assertEquals("input",activities.get(0).path("ActivityType").asText());
    assertEquals(10,activities.get(1).path("ActivityPara").path("TranscodeTask").path("Definition").asLong());
    assertFalse(activities.get(1).path("ActivityPara").path("TranscodeTask").has("OverrideParameter"));
    assertEquals(12000,activities.get(2).path("ActivityPara").path("TranscodeTask").path("OverrideParameter").path("VideoTemplate").path("Bitrate").asInt());
    assertEquals(3,activities.get(2).path("ReardriveIndex").get(0).asInt());
    journal=new ProviderJournal(jdbc,json);answer=scheduleResult(1920,1080,false).toString();
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,adapter().poll(route.taskId(),request,route).status());
  }
  @Test void singleTemplateBitrateUsesFrozenActivitiesAndKeepsRealDefinitionDiagnostics() throws Exception {
    var old=settings.mpsConfiguration();
    var template=new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.EnhancementTemplate("fullhd","1080p",List.of("720p"),true,"br",20L,Map.of());
    settings=settings.withMpsConfiguration(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration(1,old.secretId(),old.secretKey(),old.region(),old.bucket(),old.bucketRegion(),old.outputDir(),11,12,13,List.of(template),List.of(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.BitrateProfile("br","Delivery",12000))));
    scheduleMetadata(108307,1920,1080,true);
    var request=request(MediaType.VIDEO,1,"1080p");var route=scheduleRoute("single-bitrate");
    answer="{\"id\":\"native-receipt\"}";adapter().submit(route.taskId(),"owner",request,route);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";adapter().poll(route.taskId(),request,route);
    answer="{\"Response\":{\"TaskId\":\"schedule-receipt\"}}";adapter().poll(route.taskId(),request,route);
    var payload=bodies.getLast();assertFalse(payload.has("ScheduleId"));assertEquals(3,payload.path("Activities").size());
    assertEquals(12000,payload.path("Activities").get(1).path("ActivityPara").path("TranscodeTask").path("OverrideParameter").path("VideoTemplate").path("Bitrate").asInt());
    answer=scheduleResult(1920,1080,true).toString();assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,adapter().poll(route.taskId(),request,route).status());
    assertNull(adapter().diagnostics(route.taskId()).enhancement().scheduleId());assertEquals(20,adapter().diagnostics(route.taskId()).enhancement().definitionId());
  }
  @Test void newPoliciesRejectWrongTierOrRatioForScheduleAndDefinitionBeforeCharging() throws Exception {
    scheduledSettings(scheduleEnvironment());
    for(boolean useSchedule:List.of(true,false)) for(int[] size:List.of(new int[]{1280,720},new int[]{1440,1440})) {
      var config=policyConfiguration(true);
      if(!useSchedule) config=new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration(config.version(),config.secretId(),config.secretKey(),config.region(),config.bucket(),config.bucketRegion(),config.outputDir(),11,12,13,
          List.of(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.EnhancementTemplate("fullhd","1080p",List.of("720p"),true,null,20L,Map.of())),List.of());
      var saved=config;settings=settings.withMpsConfiguration(saved);scheduleMetadata(108307,size[0],size[1],false);
      String id="wrong-size-"+useSchedule+"-"+size[0];
      assertThrows(DefinitiveRejectionException.class,()->adapter().requireAvailable(request(MediaType.VIDEO,1,"1080p"),scheduleRoute(id)));assertNull(journal.find(id));
      var store=new com.xzwl.cloud.media.application.provider.MpsConfigurationStore() { public Configuration load(){return saved;} public Configuration save(Configuration value){throw new AssertionError();} };
      var http=new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2));
      var probe=new TencentMpsConfigurationProbe(store,settings,new TencentMpsAdapter(http,clock),clock);
      assertFalse(probe.test(saved.version(),"fullhd","16:9").success());
    }
    assertTrue(headers.stream().allMatch(h->h.get("X-tc-action").getFirst().startsWith("Describe")));
  }
  @Test void allMetadataReadsShareOnePreflightBudgetAndNeverContinueAfterExpiry() throws Exception {
    scheduledSettings(scheduleEnvironment());scheduleMetadata(108307,1920,1080,false);
    settings=settings.withConnection(new Connection(0,settings.providerId,settings.arkBase,settings.arkKey,1,0));
    for(long wait:List.of(700L,1200L)) {
      posts.set(0);delay=wait;
      long start=System.nanoTime();
      assertThrows(DependencyUnavailableException.class,()->adapter(Duration.ofSeconds(3)).requireAvailable(request(MediaType.VIDEO,1,"1080p"),scheduleRoute("slow-"+wait)));
      assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1700);
      assertEquals(wait==700?2:1,posts.get());assertNull(journal.find("slow-"+wait));
    }
    assertTrue(headers.stream().noneMatch(h->h.getOrDefault("X-tc-action",List.of()).contains("ProcessMedia")));
  }
  @Test void acceptedJournalRecoversWithHistoricalCapabilityMetadataWhileNewWorkIsRejected() {
    String historical="{\"type\":\"VIDEO\",\"resolutions\":[\"480p\"],\"aspectRatios\":[\"16:9\"],\"minDurationSeconds\":1,\"maxDurationSeconds\":30}";
    String model="doubao-seedance-2-5-260628";
    var oldRoute=new RouteSnapshot("old-capability","public-model","ark","Ark",endpoint,model,"{}",historical,clock.instant());
    journal.create(oldRoute.taskId(),new ProviderSettings.Plan(endpoint,model,MediaType.VIDEO,"480p","480p","16:9",null,null,null,null,0));
    var request=request(MediaType.VIDEO,1,"480p");
    assertDoesNotThrow(()->adapter().requireAvailable(request,oldRoute));
    answer="{\"id\":\"legacy-upstream\"}";adapter().submit(oldRoute.taskId(),"owner",request,oldRoute);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/legacy.mp4\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,adapter().poll(oldRoute.taskId(),request,oldRoute).status());
    var fresh=new RouteSnapshot("new-capability","public-model","ark","Ark",endpoint,model,"{}",historical,clock.instant());
    assertThrows(DefinitiveRejectionException.class,()->adapter().requireAvailable(request,fresh));assertNull(journal.find(fresh.taskId()));
  }
  @Test void capabilitySubsetsRejectExpandedSpecificationsAndWrongImageDimensions() {
    String valid="{\"type\":\"IMAGE\",\"resolutions\":[\"2K\"],\"aspectRatios\":[\"1:1\"],\"sizes\":{\"2K\":{\"1:1\":\"2048x2048\"}}}";
    for(String config:List.of(valid,valid.replace("2048x2048","4096x4096"),valid.replace("2K","8K"),valid.replace("\"sizes\"", "\"maxCount\":5,\"sizes\""))) {
      var route=new RouteSnapshot("subset","public-model","ark","Ark",endpoint,"doubao-seedream-4-0-250828","{}",config,clock.instant());
      if(config.equals(valid)) assertDoesNotThrow(()->settings.plan(request(MediaType.IMAGE,1,"2K"),route));
      else assertThrows(DefinitiveRejectionException.class,()->settings.plan(request(MediaType.IMAGE,1,"2K"),route));
    }
    var capped=new RouteSnapshot("count-subset","public-model","ark","Ark",endpoint,"doubao-seedream-4-0-250828","{}",valid.replace("\"sizes\"","\"maxCount\":1,\"sizes\""),clock.instant());
    assertThrows(DefinitiveRejectionException.class,()->settings.plan(request(MediaType.IMAGE,2,"2K"),capped));
    for(String range:List.of("\"minDurationSeconds\":1,\"maxDurationSeconds\":15","\"minDurationSeconds\":4,\"maxDurationSeconds\":31","\"minDurationSeconds\":4.5,\"maxDurationSeconds\":15")) {
      String config="{\"type\":\"VIDEO\",\"resolutions\":[\"480p\"],\"aspectRatios\":[\"16:9\"],"+range+"}";
      var route=new RouteSnapshot("duration-subset","public-model","ark","Ark",endpoint,"doubao-seedance-2-5-260628","{}",config,clock.instant());
      assertThrows(DefinitiveRejectionException.class,()->settings.plan(request(MediaType.VIDEO,1,"480p"),route));
    }
  }
  private Map<String,String> scheduleEnvironment() {
    var env=new HashMap<String,String>();env.put("MEDIA_MPS_ENDPOINT",endpoint);
    String[] tiers={"720P","1080P","4K"}, shapes={"WIDE","SQUARE","STANDARD","ULTRAWIDE"};
    long[][] ids={{108412,108413,108414,108415},{108307,108308,108309,108310},{108311,108312,108313,108314}};
    for(int r=0;r<3;r++) for(int c=0;c<4;c++) env.put("TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_"+tiers[r]+"_"+shapes[c],Long.toString(ids[r][c]));
    return env;
  }
  private void scheduledSettings(Map<String,String> env) {
    settings=ProviderSettings.from(env,json).withConnection(settings.arkConnection()).withMpsConfiguration(settings.mpsConfiguration());
  }
  private RouteSnapshot scheduleRoute(String id) {
    return new RouteSnapshot(id,"public-model","ark","Ark",endpoint,"seedance-fixture","{}", """
        {"type":"VIDEO","resolutions":["480p","720p","1080p","4K"],"aspectRatios":["16:9","9:16","1:1","4:3","3:4","21:9"],"minDurationSeconds":4,"maxDurationSeconds":15}
        """,clock.instant());
  }
  private void scheduleMetadata(long id,int width,int height,boolean single) {
    var definitions=single?List.of(20):List.of(10,20);
    var activities=new ArrayList<Object>();
    activities.add(Map.of("ActivityType","input","ReardriveIndex",List.of(1)));
    for(int i=0;i<definitions.size();i++) activities.add(Map.of("ActivityType","action-trans","ReardriveIndex",List.of(i+2),
        "ActivityPara",Map.of("TranscodeTask",Map.of("Definition",definitions.get(i)))));
    activities.add(Map.of("ActivityType","output","ReardriveIndex",List.of()));
    actionAnswers.put("DescribeSchedules",json.valueToTree(Map.of("Response",Map.of("ScheduleInfoSet",List.of(Map.of("ScheduleId",id,"Activities",activities))))).toString());
    actionAnswers.put("DescribeTranscodeTemplates",json.valueToTree(Map.of("Response",Map.of("TranscodeTemplateSet",List.of(Map.of("Definition",20,"VideoTemplate",Map.of("Width",width,"Height",height,"ResolutionAdaptive","open")))))).toString());
  }
  private com.fasterxml.jackson.databind.node.ObjectNode scheduleResult(int width,int height,boolean single) {
    var results=json.createArrayNode();
    for(int definition:single?List.of(20):List.of(20,10)) { // response order must not select the intermediate output
      var activity=results.addObject();activity.put("ActivityType","Transcode");
      var task=activity.putObject("ActivityResItem").putObject("TranscodeTask");
      task.put("Status","SUCCESS");task.put("ErrCode",0);task.put("ErrCodeExt","");task.putObject("Input").put("Definition",definition);
      task.put("BeginProcessTime","2026-09-21T01:44:14Z");task.put("FinishTime",definition==20?"2026-09-21T01:45:26Z":"2026-09-21T01:45:19Z");
      var output=task.putObject("Output");output.put("Definition",definition);output.put("Width",width);output.put("Height",height);
      output.put("Path",definition==20?"/output/final.mp4":"/output/intermediate.mp4");
      output.putObject("OutputStorage").put("Type","COS").putObject("CosOutputStorage").put("Bucket","fixture-123").put("Region","ap-guangzhou");
    }
    var response=json.createObjectNode();var schedule=response.putObject("Response").put("Status","FINISH").put("TaskType","ScheduleTask").putObject("ScheduleTask");
    schedule.put("TaskId","schedule-receipt");schedule.put("Status","FINISH");schedule.put("ErrCode",0);schedule.set("ActivityResultSet",results);
    return response;
  }
  @Test void scheduleMappingsAndDimensionsAreFrozenBeforeGenerationForEveryRatio() throws Exception {
    scheduledSettings(scheduleEnvironment());
    String[] tiers={"720p","1080p","4K"}, ratios={"16:9","9:16","1:1","4:3","3:4","21:9"};
    long[][] ids={{108412,108412,108413,108414,108414,108415},{108307,108307,108308,108309,108309,108310},{108311,108311,108312,108313,108313,108314}};
    int[][][] sizes={{{1280,720},{1280,720},{960,960},{1112,834},{1112,834},{1470,630}},
        {{1920,1080},{1920,1080},{1440,1440},{1664,1248},{1664,1248},{2206,946}},
        {{3840,2160},{3840,2160},{2880,2880},{3326,2494},{3326,2494},{4398,1886}}};
    for(int r=0;r<3;r++) for(int c=0;c<6;c++) {
      String id="mapped-"+r+"-"+c;boolean portrait=c==1||c==4;
      scheduleMetadata(ids[r][c],sizes[r][c][0],sizes[r][c][1],r==0);
      var request=new TaskRequest(MediaType.VIDEO,"public-model","cloud",ratios[c],tiers[r],1,5,List.of());
      var route=scheduleRoute(id);adapter().requireAvailable(request,route);
      var plan=journal.executionPlan(id);var saved=json.valueToTree(plan).path("schedule");
      assertEquals(ids[r][c],saved.path("id").asLong());assertEquals(20,plan.template());
      assertEquals(sizes[r][c][portrait?1:0],saved.path("width").asInt());
      assertEquals(sizes[r][c][portrait?0:1],saved.path("height").asInt());
      assertEquals(r==0?1:2,saved.path("definitions").size());
      assertEquals(ids[r][c],bodies.get(bodies.size()-2).path("ScheduleIds").get(0).asLong());
      assertEquals(20,bodies.getLast().path("Definitions").get(0).asLong());
      int sent=posts.get();journal=new ProviderJournal(jdbc,json);adapter().requireAvailable(request,route);assertEquals(sent,posts.get());
      var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)),clock);
      var projected=mps.prepare(settings.enhancementPlan(settings.projectedTemplates().get(r),ratios[c]),settings);
      assertEquals(saved.path("width").asInt(),projected.enhancement().width());assertEquals(saved.path("height").asInt(),projected.enhancement().height());
      answer=scheduleResult(saved.path("width").asInt(),saved.path("height").asInt(),r==0).toString();
      assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,mps.poll("schedule-receipt",plan,settings).status());
    }
    assertEquals(90,posts.get()); // legacy + policy read-only metadata and task-detail queries only
    int sent=posts.get();adapter().requireAvailable(request(MediaType.VIDEO,1,"480p"),scheduleRoute("native"));assertEquals(sent,posts.get());
    assertTrue(json.valueToTree(journal.executionPlan("native")).path("schedule").isNull());
  }
  @Test void twoKEnhancementPreflightsEveryRatioAndRejectsWrongDimensions() throws Exception {
    var template=new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.EnhancementTemplate("2k","2K",List.of("480p","720p","1080p"),true,null,null,
        Map.of("WIDE",108307L,"SQUARE",108308L,"STANDARD",108309L,"ULTRAWIDE",108310L));
    var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)),clock);
    String[] ratios={"16:9","9:16","1:1","4:3","3:4","21:9"};
    long[] ids={108307,108307,108308,108309,108309,108310};
    int[][] sizes={{2560,1440},{2560,1440},{1920,1920},{2218,1664},{2218,1664},{2938,1260}};
    for(int i=0;i<ratios.length;i++) {
      scheduleMetadata(ids[i],sizes[i][0],sizes[i][1],true);
      var plan=mps.prepare(settings.enhancementPlan(template,ratios[i]),settings);
      boolean portrait=i==1||i==4;
      assertEquals(sizes[i][portrait?1:0],plan.enhancement().width());
      assertEquals(sizes[i][portrait?0:1],plan.enhancement().height());
    }
    scheduleMetadata(108307,1920,1080,true);
    assertThrows(DefinitiveRejectionException.class,()->mps.prepare(settings.enhancementPlan(template,"16:9"),settings));
    assertTrue(headers.stream().noneMatch(h->h.getOrDefault("X-tc-action",List.of()).contains("ProcessMedia")));
  }
  @Test void schedulePreflightRejectsMissingMappingsAndInvalidMetadataWithoutCreatingOperation() throws Exception {
    var env=scheduleEnvironment();env.remove("TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_720P_WIDE");scheduledSettings(env);
    assertThrows(DependencyUnavailableException.class,()->adapter().requireAvailable(request(MediaType.VIDEO,1,"720p"),scheduleRoute("missing")));
    assertNull(journal.find("missing"));assertEquals(0,posts.get());
    for(String invalid:List.of("0","-1","1.5","bad")) {
      env=scheduleEnvironment();env.put("TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_720P_WIDE",invalid);
      var bad=env;assertThrows(IllegalArgumentException.class,()->ProviderSettings.from(bad,json));
    }
    scheduledSettings(scheduleEnvironment());
    for(int fault=0;fault<5;fault++) {
      scheduleMetadata(108307,1920,1080,false);
      var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(actionAnswers.get("DescribeSchedules"));
      var schedule=(com.fasterxml.jackson.databind.node.ObjectNode)metadata.path("Response").path("ScheduleInfoSet").get(0);
      var activities=schedule.withArray("Activities");
      if(fault==0) schedule.put("ScheduleId",999);
      if(fault==1) ((com.fasterxml.jackson.databind.node.ObjectNode)activities.get(1)).putArray("ReardriveIndex").add(1);
      if(fault==2) ((com.fasterxml.jackson.databind.node.ObjectNode)activities.get(1)).withArray("ReardriveIndex").add(3);
      if(fault==3) ((com.fasterxml.jackson.databind.node.ObjectNode)activities.get(2).path("ActivityPara").path("TranscodeTask")).put("Definition",10);
      if(fault==4) actionAnswers.put("DescribeTranscodeTemplates","{\"Response\":{\"TranscodeTemplateSet\":[]}}");
      actionAnswers.put("DescribeSchedules",metadata.toString());String id="invalid-schedule-"+fault;
      assertThrows(DefinitiveRejectionException.class,()->adapter().requireAvailable(request(MediaType.VIDEO,1,"1080p"),scheduleRoute(id)));
      assertNull(journal.find(id));
    }
    assertTrue(headers.stream().noneMatch(h->h.getOrDefault("X-tc-action",List.of()).contains("ProcessMedia")));
  }
  @Test void scheduledGenerationRestartsAndPublishesOnlyFinalOutputWithPrivateCosSigning() throws Exception {
    scheduledSettings(scheduleEnvironment());scheduleMetadata(108307,1920,1080,false);
    var request=request(MediaType.VIDEO,1,"1080p");var route=scheduleRoute("scheduled");
    answer="{\"id\":\"native-receipt\"}";adapter().submit(route.taskId(),"owner",request,route);
    assertEquals("720p",bodies.getLast().path("resolution").asText());
    scheduleMetadata(999,999,999,true); // rotations must not re-plan an accepted operation
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    adapter().poll(route.taskId(),request,route);
    answer="{\"Response\":{\"TaskId\":\"schedule-receipt\"}}";adapter().poll(route.taskId(),request,route);
    assertEquals(108307,bodies.getLast().path("ScheduleId").asLong());assertFalse(bodies.getLast().has("MediaProcessTask"));
    assertEquals(route.taskId(),bodies.getLast().path("SessionId").asText());
    journal=new ProviderJournal(jdbc,json);int sent=posts.get();adapter().submit(route.taskId(),"owner",request,route);assertEquals(sent,posts.get());
    answer=scheduleResult(1920,1080,false).toString();var result=adapter().poll(route.taskId(),request,route);
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,result.status());
    assertEquals("https://fixture-123.cos.ap-guangzhou.myqcloud.com/output/final.mp4",result.outputs().getFirst().httpsUrl());
    var diagnostic=adapter().diagnostics(route.taskId()).enhancement();assertEquals("108307",diagnostic.scheduleId());assertNull(diagnostic.definitionId());
    assertEquals(2,json.valueToTree(diagnostic).path("steps").size());
    assertEquals(List.of(10L,20L),diagnostic.steps().stream().map(ProviderPort.EnhancementStep::definitionId).toList());
    assertEquals(List.of(65000L,72000L),diagnostic.steps().stream().map(ProviderPort.EnhancementStep::durationMs).toList());
    AtomicInteger archived=new AtomicInteger();
    StoragePort storage=new StoragePort() {
      public List<MediaReference> resolveReferences(String owner,List<MediaReference> references) { return references; }
      public String archive(String owner,String id,int index,String url) { throw new AssertionError("stage required"); }
      public String archive(String owner,String id,int index,String url,ArchiveStage stage) {
        assertEquals(ArchiveStage.ENHANCEMENT,stage);
        assertEquals("owner",owner);assertEquals("scheduled",id);assertEquals(0,index);assertTrue(url.endsWith("/intermediate.mp4"));
        archived.incrementAndGet();return "intermediate-file";
      }
    };
    adapter().archiveEnhancement("scheduled","owner",storage);
    journal=new ProviderJournal(jdbc,json);adapter().archiveEnhancement("scheduled","owner",storage);
    assertEquals(1,archived.get());assertEquals("intermediate-file",adapter().diagnostics("scheduled").enhancement().steps().getFirst().fileId());
    assertEquals(1,adapter().poll("scheduled",request,route).outputs().size());
    String publicJson=json.writeValueAsString(adapter().diagnostics("scheduled"));assertFalse(publicJson.contains("https://"));
    for(boolean unavailable:List.of(false,true)) {
      // Restore the persisted recovery shape with an unarchived intermediate node.
      journal.saveEnhancements(journal.find("scheduled"),List.of(
          new ProviderJournal.EnhancementStep(new ProviderPort.EnhancementOutput(10,2560,1440,65000L,"https://fixture-123.cos.ap-guangzhou.myqcloud.com/output/intermediate.mp4"),null,null),
          new ProviderJournal.EnhancementStep(new ProviderPort.EnhancementOutput(20,1920,1080,72000L,result.outputs().getFirst().httpsUrl()),null,null)));
      adapter().archiveEnhancement("scheduled","owner",new StoragePort() {
        public List<MediaReference> resolveReferences(String owner,List<MediaReference> references) { return references; }
        public String archive(String owner,String id,int index,String url) { if(unavailable) throw new DependencyUnavailableException("storage");throw new DefinitiveRejectionException("invalid"); }
      });
      assertEquals(unavailable?"STORAGE_UNAVAILABLE":"STORAGE_REJECTED",adapter().diagnostics("scheduled").enhancement().steps().getFirst().failureCode());
      assertEquals("COMPLETE",journal.find("scheduled").phase());assertEquals(1,journal.find("scheduled").outputs().size());
    }
    var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)),clock);
    var plan=journal.executionPlan(route.taskId());assertTrue(plan.template()>0); // MediaConfiguration's existing archive signer gate
    assertTrue(mps.archiveUrl(result.outputs().getFirst().httpsUrl(),settings,plan).contains("q-signature="));
    assertFalse(jdbc.queryForMap("select * from media_provider_operation").toString().contains("fixture-tencent-secret"));
    var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(plan);legacy.remove("schedule");
    assertEquals(plan.template(),json.treeToValue(legacy,ProviderSettings.Plan.class).template());
  }
  @Test void scheduleResultRejectsPartialDuplicateUnknownWrongIdentityAndInvalidFinalOutput() throws Exception {
    scheduledSettings(scheduleEnvironment());scheduleMetadata(108307,1920,1080,false);
    var request=request(MediaType.VIDEO,1,"1080p");var route=scheduleRoute("result-fixture");adapter().requireAvailable(request,route);
    var plan=journal.executionPlan(route.taskId());var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)),clock);
    for(int fault=0;fault<14;fault++) {
      var tree=scheduleResult(1920,1080,false);var schedule=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("Response").path("ScheduleTask");
      var results=schedule.withArray("ActivityResultSet");var task=(com.fasterxml.jackson.databind.node.ObjectNode)results.get(0).path("ActivityResItem").path("TranscodeTask");
      if(fault==0) results.remove(0); // intermediate remains
      if(fault==1) results.add(results.get(0).deepCopy());
      if(fault==2) task.put("Status","FAIL");
      if(fault==3) task.withObject("Output").put("Width",3840);
      if(fault==4) task.withObject("Output").withObject("OutputStorage").withObject("CosOutputStorage").put("Bucket","other-123");
      if(fault==5) task.withObject("Input").put("Definition",999);
      if(fault==6) schedule.put("TaskId","another-task");
      if(fault==7) task.put("ErrCodeExt","MediaProcessFailed");
      if(fault==8) task.withObject("Output").put("Path","/../secret");
      if(fault==9) task.withObject("Output").put("Definition",999);
      if(fault==10) schedule.put("ErrCode",1);
      var intermediate=(com.fasterxml.jackson.databind.node.ObjectNode)results.get(results.size()-1).path("ActivityResItem").path("TranscodeTask");
      if(fault==11) intermediate.withObject("Output").withObject("OutputStorage").withObject("CosOutputStorage").put("Bucket","other-123");
      if(fault==12) intermediate.withObject("Output").put("Path","/../secret");
      if(fault==13) intermediate.withObject("Output").put("Height",0);
      answer=tree.toString();var result=mps.poll("schedule-receipt",plan,settings);
      if(fault<11) assertNotEquals(ProviderPort.ProviderPoll.Status.COMPLETED,result.status(),"fault="+fault);
      else { assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,result.status());assertNull(result.enhancementOutputs().getFirst().sourceUrl());assertEquals(1,result.outputs().size()); }
    }
    answer="{\"Response\":{\"Status\":\"PROCESSING\"}}";assertEquals(ProviderPort.ProviderPoll.Status.PENDING,mps.poll("schedule-receipt",plan,settings).status());
  }


  @Test void scheduleAmbiguousSubmitNeverRepeatsAfterRestart() throws Exception {
    scheduledSettings(scheduleEnvironment());scheduleMetadata(108307,1920,1080,false);
    var request=request(MediaType.VIDEO,1,"1080p");var route=scheduleRoute("schedule-ambiguous");
    answer="{\"id\":\"native-receipt\"}";adapter().submit(route.taskId(),"owner",request,route);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    adapter().poll(route.taskId(),request,route);
    delay=250;answer="{\"Response\":{\"TaskId\":\"late-receipt\"}}";
    assertThrows(DependencyUnavailableException.class,()->adapter(Duration.ofMillis(80)).poll(route.taskId(),request,route));
    Thread.sleep(350);delay=0;
    assertEquals("MPS_SENDING",journal.find(route.taskId()).phase());assertEquals(108307,bodies.getLast().path("ScheduleId").asLong());
    int sent=posts.get();journal=new ProviderJournal(jdbc,json);
    adapter().submit(route.taskId(),"owner",request,route);
    assertEquals(ProviderPort.ProviderPoll.Status.PENDING,adapter().poll(route.taskId(),request,route).status());
    assertEquals(sent,posts.get());
  }
  @Test void legacyPlanJsonWithoutScheduleResumesTemplateEvenWhenSchedulesAreNowConfigured() throws Exception {
    var request=request(MediaType.VIDEO,1,"720p");var route=scheduleRoute("legacy-frozen");
    adapter().requireAvailable(request,route);
    var old=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(journal.executionPlan(route.taskId()));old.remove("schedule");
    jdbc.update("UPDATE media_provider_operation SET plan_json=? WHERE operation_id=?",old.toString(),route.taskId());
    scheduledSettings(scheduleEnvironment());journal=new ProviderJournal(jdbc,json);
    adapter().requireAvailable(request,route);assertEquals(0,posts.get());
    answer="{\"id\":\"legacy-native\"}";adapter().submit(route.taskId(),"owner",request,route);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";adapter().poll(route.taskId(),request,route);
    answer="{\"Response\":{\"TaskId\":\"legacy-mps\"}}";adapter().poll(route.taskId(),request,route);
    assertFalse(bodies.getLast().has("ScheduleId"));
    assertEquals(720,bodies.getLast().path("MediaProcessTask").path("TranscodeTaskSet").get(0).path("Definition").asLong());
    answer="{\"Response\":{\"Status\":\"FINISH\",\"WorkflowTask\":{\"MediaProcessResultSet\":[{\"TranscodeTask\":{\"Status\":\"SUCCESS\",\"Output\":{\"Definition\":720,\"Width\":1280,\"Height\":720,\"Path\":\"/legacy.mp4\",\"OutputStorage\":{\"Type\":\"COS\",\"CosOutputStorage\":{\"Bucket\":\"fixture-123\",\"Region\":\"ap-guangzhou\"}}}}}]}}}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,adapter().poll(route.taskId(),request,route).status());
    assertEquals(3,posts.get());assertNull(adapter().diagnostics(route.taskId()).enhancement().scheduleId());
  }

  @Test void newApiDoubaoUsesAuthenticatedJsonCatalogButPreservesGenerationAndPollingPrefix() throws Exception {
    var connection=new Connection(7,"proxy",endpoint+"/doubao/api/v3","proxy-test-key");
    var store=new com.xzwl.cloud.media.application.provider.ArkConnectionStore() {
      public Connection load(String id) { assertEquals("proxy",id); return connection; }
      public Connection save(long v,String p,String b,String k) { throw new UnsupportedOperationException(); }
      public boolean editable() { return true; }
      public boolean hasConfiguredConnection() { return true; }
    };
    var http=new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2));
    var catalog=new HttpArkModelCatalog(store,http);
    var probe=new HttpArkConnectionProbe(store,HttpClient.newHttpClient(),Duration.ofSeconds(2));
    answer="""
        {"object":"list","success":true,"data":[
          {"id":"doubao-seedance-2-5-260628","object":"model","owned_by":"doubao"},
          {"id":"doubao-seedream-4.0","object":"model"}]}
        """;
    var result=catalog.list("proxy");
    assertTrue(result.success()); assertEquals(7,result.version()); assertEquals("proxy",result.providerId());
    assertEquals("GET /v1/models",paths.getLast()); assertEquals("Bearer proxy-test-key",headers.getLast().get("Authorization").getFirst());
    var model=result.models().stream().filter(m -> m.id().equals("doubao-seedance-2-5-260628")).findFirst().orElseThrow();
    assertEquals("VIDEO",model.type()); assertEquals(ArkModelCapabilities.forModel(model.id(),"VIDEO"),model.capability());
    assertNull(result.models().stream().filter(m -> m.id().equals("doubao-seedream-4.0")).findFirst().orElseThrow().capability());
    assertTrue(probe.test("proxy").reachable()); assertEquals("GET /v1/models",paths.getLast()); assertEquals(0,posts.get());
    for(String invalid:List.of("<html>New API</html>","{\"error\":{\"message\":\"private-secret\"}}", "{\"success\":false,\"data\":[]}","{\"data\":[{}]}")) {
      answer=invalid;var failed=probe.test("proxy"); assertFalse(failed.reachable());assertFalse(failed.message().contains("private-secret"));
    }
    for(int code:new int[]{401,403,405,302,500}) {
      status=code;answer="{\"data\":[]}";assertFalse(probe.test("proxy").reachable());assertEquals("GET /v1/models",paths.getLast());
    }
    status=200;
    settings=settings.withConnection(connection);
    var config=new LinkedHashMap<String,Object>(json.convertValue(model.capability(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}));
    config.put("type","VIDEO");
    var route=new RouteSnapshot("proxy-task","public-model","proxy","Proxy",connection.baseUrl(),model.id(),"{}",json.writeValueAsString(config),clock.instant());
    var provider=adapter();var request=request(MediaType.VIDEO,1,"480p");
    answer="{\"id\":\"upstream-proxy-task\"}";provider.submit(route.taskId(),"owner",request,route);
    assertEquals("POST /doubao/api/v3/contents/generations/tasks",paths.getLast());assertEquals(1,posts.get());
    answer="{\"status\":\"running\"}";provider.poll(route.taskId(),request,route);
    assertEquals("GET /doubao/api/v3/contents/generations/tasks/upstream-proxy-task",paths.getLast());
    assertTrue(paths.stream().noneMatch(path -> path.contains("redirect-target")));
  }

  @Test void omniReferencesUseTypedRolesAndKeepPerTypeOrder() throws Exception {
    var request=json.readValue("""
        {"type":"VIDEO","model":"public-model","prompt":"图片1的人物，视频1的动作，音频1的声音",
         "aspectRatio":"16:9","resolution":"720p","count":1,"durationSeconds":5,"referenceMode":"OMNI",
         "references":[{"httpsUrl":"https://cdn.example/person.png","kind":"IMAGE"},
           {"httpsUrl":"https://cdn.example/motion.mp4","kind":"VIDEO","durationSeconds":4},
           {"httpsUrl":"https://cdn.example/voice.wav","kind":"AUDIO","durationSeconds":3},
           {"httpsUrl":"https://cdn.example/scene.png","kind":"IMAGE"}]}
        """,TaskRequest.class);
    var config="""
        {"type":"VIDEO","resolutions":["720p"],"aspectRatios":["16:9"],"minDurationSeconds":4,"maxDurationSeconds":15}
        """;
    var route=new RouteSnapshot("omni-typed","public-model","ark","Ark","https://ark.example","doubao-seedance-2-0-260128","{}",config,clock.instant());
    answer="{\"id\":\"omni-upstream\"}";
    var provider=adapter();provider.submit(route.taskId(),"owner",request,route);
    var content=bodies.getLast().path("content");
    assertEquals(5,content.size());
    assertEquals("reference_image",content.get(1).path("role").asText());
    assertEquals("https://cdn.example/person.png",content.get(1).path("image_url").path("url").asText());
    assertEquals("reference_video",content.get(2).path("role").asText());
    assertEquals("https://cdn.example/motion.mp4",content.get(2).path("video_url").path("url").asText());
    assertEquals("reference_audio",content.get(3).path("role").asText());
    assertEquals("https://cdn.example/voice.wav",content.get(3).path("audio_url").path("url").asText());
    assertEquals("https://cdn.example/scene.png",content.get(4).path("image_url").path("url").asText());
    provider.submit(route.taskId(),"owner",request,route);
    assertEquals(1,posts.get());
    assertFalse(bodies.getFirst().has("omni_reference_task_type"));
    var v25=new RouteSnapshot("omni-v25","public-model","ark","Ark","https://ark.example",
        "doubao-seedance-2-5-260628","{}",config,clock.instant());
    provider.submit(v25.taskId(),"owner",request,v25);
    assertEquals("reference",bodies.getLast().path("omni_reference_task_type").asText());
    assertEquals("16:9",bodies.getLast().path("ratio").asText());
    assertEquals("OMNI",json.readTree(json.writeValueAsString(request)).path("referenceMode").asText());
    var legacy=json.readValue("{\"type\":\"VIDEO\",\"prompt\":\"old task\",\"references\":[{\"fileId\":\"image\"}]}",TaskRequest.class);
    assertEquals(ReferenceMode.FIRST_LAST_FRAME,legacy.referenceMode());
    assertEquals(MediaReference.Kind.IMAGE,legacy.references().getFirst().kind());
  }

  RouteSnapshot zexRoute(String id,String model,String type) throws Exception {
    var capability=ZexMediaAdapter.capability(model);
    var config=new LinkedHashMap<String,Object>();
    config.put("type",type);config.put("resolutions",capability.resolutions());config.put("aspectRatios",capability.aspectRatios());
    config.put("sizes",capability.sizes());config.put("minDurationSeconds",capability.minDurationSeconds());config.put("maxDurationSeconds",capability.maxDurationSeconds());
    return new RouteSnapshot(id,"public-model","zex","生图","https://zexapi.com/v1",model,"{}",json.writeValueAsString(config),clock.instant());
  }
  @Test void asyncImagesPersistReceiptsPollAfterRestartAndNeverResubmit() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-async-key"));
    for(String model:List.of("gpt-image-2","gpt-image-2-2K","gpt-image-2-4K","gpt-image-2.5","gpt-image-2.5-flare","gpt-image-2.5-sunburst","nano_banana_2","nano_banana_2-2K","nano_banana_2-4K","nano_banana_pro-1K","nano_banana_pro-2K","nano_banana_pro-4K")) {
      var c=ZexMediaAdapter.capability(model);assertNotNull(c,model);
      for(String resolution:c.resolutions()) {
      var request=new TaskRequest(MediaType.IMAGE,"public-model","async image","16:9",resolution,1,0,
          java.util.Collections.nCopies(8,new MediaReference(null,"https://cdn.example/reference.png")));
      var route=zexRoute("async-"+model+resolution,model,"IMAGE");
      String upstream="receipt-"+model;
      answer="{\"id\":\""+upstream+"\",\"object\":\"video\",\"status\":\"queued\"}";
      var provider=adapter();provider.submit(route.taskId(),"owner",request,route);
      assertEquals("ARK_RUNNING",journal.find(route.taskId()).phase());
      assertEquals(upstream,journal.find(route.taskId()).upstream());assertEquals(MediaType.IMAGE,journal.find(route.taskId()).plan().type());
      assertEquals("POST /v1/videos",paths.getLast());
      var body=bodies.getLast();assertEquals(model,body.path("model").asText());assertEquals("16:9",body.path("aspect_ratio").asText());
      assertEquals(8,body.path("images").size());
      boolean tiered=Set.of("gpt-image-2.5-flare","gpt-image-2.5-sunburst").contains(model);
      assertEquals(tiered?Set.of("model","prompt","aspect_ratio","images","image_size"):Set.of("model","prompt","aspect_ratio","images"),json.convertValue(body,Map.class).keySet());
      if(tiered) assertEquals(resolution,body.path("image_size").asText());
      int sent=posts.get();
      journal=new ProviderJournal(jdbc,json);provider=adapter();
      provider.submit(route.taskId(),"owner",request,route);assertEquals(sent,posts.get());
      answer="{\"id\":\""+upstream+"\",\"status\":\"in_progress\"}";
      assertEquals(ProviderPort.ProviderPoll.Status.PENDING,provider.poll(route.taskId(),request,route).status());
      answer="{\"id\":\""+upstream+"\",\"status\":\"completed\",\"url\":\"https://cdn.example/result.png\"}";
      assertEquals("https://cdn.example/result.png",provider.poll(route.taskId(),request,route).outputs().getFirst().httpsUrl());
      assertEquals("GET /v1/videos/"+upstream,paths.getLast());assertEquals(sent,posts.get());
      assertEquals("Bearer zex-async-key",headers.getLast().get("Authorization").getFirst());
    }
    }
    for(String model:List.of("image2","gpt-image2","gemini-3-pro-image-preview","gemini-3.1-flash-image-preview"))
      assertNull(ZexMediaAdapter.capability(model),"sync models cannot be offered for new tasks");
  }
  @Test void asyncImagesDoNotRepeatAmbiguousPostAndRejectInvalidRequestsBeforeSending() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-async-key"));
    var route=zexRoute("async-uncertain","gpt-image-2","IMAGE");
    var request=new TaskRequest(MediaType.IMAGE,"public-model","prompt","1K",1,0,List.of());
    answer="{\"status\":\"queued\"}";
    assertThrows(DependencyUnavailableException.class,()->adapter().submit(route.taskId(),"owner",request,route));
    assertEquals("ARK_SENDING",journal.find(route.taskId()).phase());assertEquals(1,posts.get());
    journal=new ProviderJournal(jdbc,json);
    assertThrows(DependencyUnavailableException.class,()->adapter().submit(route.taskId(),"owner",request,route));assertEquals(1,posts.get());
    for(var invalid:List.of(new TaskRequest(MediaType.IMAGE,"public-model","prompt","2K",1,0,List.of()),
        new TaskRequest(MediaType.IMAGE,"public-model","prompt","1K",2,0,List.of()),
        request.withReferences(java.util.Collections.nCopies(9,new MediaReference(null,"https://cdn.example/ref.png")))))
      assertThrows(DefinitiveRejectionException.class,()->settings.plan(invalid,route));
    var banana=zexRoute("banana-limit","nano_banana_pro-1K","IMAGE");
    assertThrows(DefinitiveRejectionException.class,()->settings.plan(new TaskRequest(MediaType.IMAGE,"public-model","x".repeat(10001),"1K",1,0,List.of()),banana));
    assertEquals(1,posts.get());
  }

  @Test void imageQueryFailuresRemainRunningUntilExplicitUpstreamOutcome() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-async-key"));
    var route=zexRoute("query-recovery","gpt-image-2.5-sunburst","IMAGE");
    var request=new TaskRequest(MediaType.IMAGE,"public-model","a cloud","16:9","2K",1,0,List.of());
    var provider=adapter(); answer="{\"id\":\"existing-upstream\"}";
    provider.submit(route.taskId(),"owner",request,route);
    for(int code:List.of(400,401,403,404,422,429,500)) {
      status=code; answer="{\"error\":{\"code\":\"QUERY_UNAVAILABLE\"}}";
      assertThrows(DependencyUnavailableException.class,()->provider.poll(route.taskId(),request,route));
      assertEquals("ARK_RUNNING",journal.find(route.taskId()).phase());
    }
    status=200; answer="{\"error\":{\"code\":\"QUERY_UNAVAILABLE\"}}";
    assertThrows(DependencyUnavailableException.class,()->provider.poll(route.taskId(),request,route));
    answer="{\"id\":\"existing-upstream\",\"status\":\"completed\"}";
    assertThrows(DependencyUnavailableException.class,()->provider.poll(route.taskId(),request,route));
    answer="{\"id\":\"existing-upstream\",\"status\":\"completed\",\"url\":\"https://cdn.example/result.png\"}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,provider.poll(route.taskId(),request,route).status());
    assertEquals(1,posts.get());
  }

  @Test void explicitImageFailurePreservesProviderCodeInJournalAndMapsSafeUserReason() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-async-key"));
    var route=zexRoute("explicit-failure","gpt-image-2.5-sunburst","IMAGE");
    var request=new TaskRequest(MediaType.IMAGE,"public-model","a cloud","16:9","2K",1,0,List.of());
    var provider=adapter();answer="{\"id\":\"upstream-failed\"}";provider.submit(route.taskId(),"owner",request,route);
    answer="{\"id\":\"upstream-failed\",\"status\":\"failed\",\"error\":{\"code\":\"CONTENT_REJECTED\",\"message\":\"zex-async-key\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.FAILED,provider.poll(route.taskId(),request,route).status());
    assertEquals("CONTENT_REJECTED",provider.failure(route.taskId()).code());
    assertTrue(MediaFailureMessages.message("PROVIDER_FAILED",provider.failure(route.taskId())).contains("内容审核"));
    assertFalse(provider.failure(route.taskId()).toString().contains("zex-async-key"));
  }

  @Test void zexCatalogDisablesSyncModelsAndPreservesVideoProtocolAndFrozenTaskAddress() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-test-key"));
    var http=new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2));
    var conn=settings.arkConnection();
    var store=new com.xzwl.cloud.media.application.provider.ArkConnectionStore() {
      public Connection load(String id) { assertEquals("zex",id); return conn; }
      public Connection save(long v,String p,String b,String k){throw new UnsupportedOperationException();}
      public boolean editable(){return true;} public boolean hasConfiguredConnection(){return true;}
    };
    answer="{\"data\":[{\"id\":\"gpt-image2\"},{\"id\":\"gemini-3-pro-image-preview\"},{\"id\":\"omni_flash-10s\"},{\"id\":\"unknown-model\"}]}";
    var catalog=new HttpArkModelCatalog(store,http).list("zex");
    assertTrue(catalog.success());assertEquals(4,catalog.models().size());
    assertEquals(1,catalog.models().stream().filter(m->m.capability()!=null).count());
    assertEquals(2,catalog.models().stream().filter(m->m.status().equals("SynchronousOnly")).count());
    assertTrue(new HttpArkConnectionProbe(store,HttpClient.newHttpClient(),Duration.ofSeconds(2)).test("zex").reachable());
    assertEquals(List.of("GET /v1/models","GET /v1/models"),paths);
    var provider=adapter();
    var video=new TaskRequest(MediaType.VIDEO,"public-model","a cloud","16:9","720p",1,10,List.of());
    var videoRoute=zexRoute("zex-video","omni_flash-10s","VIDEO");
    answer="{\"id\":\"video-fixture\",\"status\":\"queued\"}";
    provider.submit("zex-video","owner",video,videoRoute);
    assertEquals("POST /v1/videos",paths.getLast());assertEquals("1280x720",bodies.getLast().path("size").asText());
    assertFalse(bodies.getLast().has("content"));assertFalse(journal.find("zex-video").plan().enhanced());
    answer="{\"id\":\"video-fixture\",\"status\":\"completed\",\"video_url\":\"https://cdn.example/video.mp4\"}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,provider.poll("zex-video",video,videoRoute).status());
    assertEquals("GET /v1/videos/video-fixture",paths.getLast());
    for(var header:headers) assertEquals("Bearer zex-test-key",header.get("Authorization").getFirst());
    int before=posts.get();
    var bad=new RouteSnapshot("bad","public-model","zex","生图","https://zexapi.com/v1","unknown","{}",videoRoute.modelConfigurationJson(),clock.instant());
    assertThrows(DefinitiveRejectionException.class,()->provider.submit("bad","owner",video,bad));assertEquals(before,posts.get());
    answer="{\"id\":\"pending-fixture\",\"status\":\"queued\"}";
    var pending=zexRoute("zex-pending","omni_flash-10s","VIDEO");provider.submit("zex-pending","owner",video,pending);
    settings=settings.withConnection(new Connection(2,"zex",endpoint+"/api/v3","new-test-key"));
    assertThrows(DependencyUnavailableException.class,()->adapter().poll("zex-pending",video,pending));
  }

  @Test void zexRejectsUnadaptedRequestsAndHandlesVideoFailures() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-test-key"));
    var provider=adapter();
    assertThrows(DefinitiveRejectionException.class,()->ZexMediaAdapter.plan(endpoint+"/v1",
        new TaskRequest(MediaType.IMAGE,"public-model","test","2K",2,0,List.of()),"gpt-image2"));
    assertThrows(DefinitiveRejectionException.class,()->ZexMediaAdapter.plan(endpoint+"/v1",
        new TaskRequest(MediaType.VIDEO,"public-model","test","720p",1,10,List.of()),"omni_flash-10s-fl"));
    assertThrows(DefinitiveRejectionException.class,()->ZexMediaAdapter.plan(endpoint+"/v1",
        new TaskRequest(MediaType.IMAGE,"public-model","test","2K",1,0,List.of()),"image2"));
    var video=new TaskRequest(MediaType.VIDEO,"public-model","test","720p",1,10,List.of());
    var videoRoute=zexRoute("failed-video","omni_flash-10s","VIDEO");
    answer="{\"id\":\"poll-test\",\"status\":\"queued\"}";provider.submit("failed-video","owner",video,videoRoute);
    answer="{\"id\":\"wrong-task\",\"status\":\"completed\",\"url\":\"https://cdn.example/wrong.mp4\"}";
    assertThrows(DependencyUnavailableException.class,()->provider.poll("failed-video",video,videoRoute));
    answer="{\"id\":\"poll-test\",\"status\":\"processing\"}";
    assertEquals(ProviderPort.ProviderPoll.Status.PENDING,provider.poll("failed-video",video,videoRoute).status());
    answer="{\"id\":\"poll-test\",\"status\":\"failed\",\"error\":{\"message\":\"zex-test-key\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.FAILED,provider.poll("failed-video",video,videoRoute).status());
    assertFalse(provider.failure("failed-video").toString().contains("zex-test-key"));
  }

  @Test void configuredZexCatalogQuoteAndAdapterAgreeOnOmniFramesAndImageReferences() throws Exception {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","zex-test-key"));
    for(String model:List.of("gpt-image-2-2K","omni_flash-10s-fl")) {
      boolean image=model.equals("gpt-image-2-2K");
      var route=zexRoute("catalog-"+model,model,image?"IMAGE":"VIDEO");
      Map<String,Object> config=json.convertValue(json.readTree(route.modelConfigurationJson()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
      config.put("displayName",model);config.put("maxCount",1);
      var routing=new RoutingPort() {
        public RouteSnapshot route(String id,String requested){return route;}
        public List<Model> models(){return List.of(new Model("public-model",model,config));}
      };
      var billing=org.mockito.Mockito.mock(BillingPort.class);
      org.mockito.Mockito.when(billing.availablePrices("owner")).thenReturn(List.of(new BillingPort.AvailablePrice("public-model",image?"2K":"720p",BigDecimal.ONE)));
      var request=new TaskRequest(image?MediaType.IMAGE:MediaType.VIDEO,"public-model","reference test","16:9",image?"2K":"720p",1,image?0:10,
          List.of(new MediaReference(null,"https://cdn.example/first.png"),new MediaReference(null,"https://cdn.example/last.png")));
      org.mockito.Mockito.when(billing.quote("owner",request)).thenReturn(BigDecimal.ONE);
      var catalog=new com.xzwl.cloud.media.application.catalog.GenerationCatalogService(routing,billing);
      assertEquals(image?8:2,catalog.options("owner").getFirst().maxReferenceImages());
      assertEquals(BigDecimal.ONE,catalog.quote("owner",request));
      answer="{\"id\":\"async-receipt\",\"status\":\"queued\"}";
      adapter().submit(route.taskId(),"owner",request,route);
      assertEquals(2,bodies.getLast().path("images").size());
      assertEquals("https://cdn.example/first.png",bodies.getLast().path("images").get(0).asText());
      assertEquals("https://cdn.example/last.png",bodies.getLast().path("images").get(1).asText());
    }
  }

  @Test void savedProviderRowsDriveGenerationCatalogProbeAndOriginalTaskPolling() throws Exception {
    for (String migration:List.of("V4__media_ark_connection.sql","V5__media_ark_plaintext_key.sql","V7__media_provider_connections.sql","V19__media_connection_request_policy.sql")) {
      try(var in=getClass().getResourceAsStream("/db/media-migration/common/"+migration)) {
        for(String sql:new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))
          if(!sql.isBlank()) jdbc.execute(sql);
      }
    }
    var store=new JdbcArkConnectionStore(jdbc,"",new Connection(0,"environment","",""),Set.of("127.0.0.1"));
    for(String id:List.of("ark","image")) store.save(0,id,endpoint+"/"+id+(id.equals("ark") ? "/api/v3/" : "/custom/"),id+"-fixture-key");
    var http=new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2));
    var provider=new ProductionProviderPort(id -> settings.withConnection(store.load(id)),journal,new ArkMediaAdapter(http),new TencentMpsAdapter(http,clock));
    var video=request(MediaType.VIDEO,1,"480p");
    var a=new RouteSnapshot("task-a","public-model","ark","Ark",endpoint+"/ark/api/v3","seedance-fixture","{}","{}",clock.instant());
    var b=new RouteSnapshot("task-b","public-model","image","Image","https://other.example","seedance-fixture","{}","{}",clock.instant());
    answer="{\"id\":\"upstream-a\"}";
    provider.requireAvailable(video,a); provider.submit("task-a","owner",video,a);
    assertEquals("Bearer ark-fixture-key",headers.getLast().get("Authorization").getFirst());
    assertEquals("POST /ark/api/v3/contents/generations/tasks",paths.getLast());
    answer="{\"id\":\"upstream-b\"}";
    provider.requireAvailable(video,b); provider.submit("task-b","owner",video,b);
    assertEquals("Bearer image-fixture-key",headers.getLast().get("Authorization").getFirst());
    assertEquals("POST /image/custom/contents/generations/tasks",paths.getLast());
    // Saving B after A was submitted cannot change A's recorded provider or credentials.
    jdbc.update("UPDATE media_provider_connection SET api_key='image-rotated-key',version=2 WHERE provider_id='image'");
    answer="{\"status\":\"running\"}";
    provider.poll("task-a",video,a);
    assertEquals("Bearer ark-fixture-key",headers.getLast().get("Authorization").getFirst());
    assertEquals("GET /ark/api/v3/contents/generations/tasks/upstream-a",paths.getLast());
    provider.poll("task-b",video,b);
    assertEquals("Bearer image-rotated-key",headers.getLast().get("Authorization").getFirst());
    var catalog=new HttpArkModelCatalog(store,http);
    var probe=new HttpArkConnectionProbe(store,HttpClient.newHttpClient(),Duration.ofSeconds(2));
    for(String id:List.of("ark","image")) {
      answer="{\"data\":[]}";
      assertEquals(id,catalog.list(id).providerId());
      assertEquals("GET /"+id+(id.equals("ark") ? "/api/v3" : "/custom")+"/models",paths.getLast());
      assertTrue(probe.test(id).reachable());
      assertEquals("GET /"+id+(id.equals("ark") ? "/api/v3" : "/custom")+"/contents/generations/tasks",paths.getLast());
      assertEquals("Bearer "+store.load(id).apiKey(),headers.getLast().get("Authorization").getFirst());
    }
    int before=paths.size();
    assertFalse(catalog.list("unconfigured").success());
    assertFalse(probe.test("unconfigured").reachable());
    var missing=new RouteSnapshot("missing","public-model","unconfigured","Missing","https://missing.example","seedance-fixture","{}","{}",clock.instant());
    assertThrows(DependencyUnavailableException.class,()->provider.requireAvailable(video,missing));
    assertEquals(before,paths.size());
    assertEquals(2,posts.get());
  }

  @Test void nativeGuideUsesPlatformReceiptAndDocumentedCompletionFields() {
    var ark=new ArkMediaAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)));
    var plan=new ProviderSettings.Plan(endpoint+"/doubao/api/v3","doubao-seedance-2-5-260628",MediaType.VIDEO,"480p","480p","16:9",null,null,null,null,0);
    answer="{\"task_id\":\"platform-task\",\"upstream_task_id\":\"cgt-original\"}";
    assertEquals("platform-task",ark.submitVideo(request(MediaType.VIDEO,1,"480p"),plan,"proxy-key"));
    for(String field:List.of("video_url","official_url")) {
      answer="{\"task_id\":\"platform-task\",\"upstream_task_id\":\"cgt-original\",\"status\":\"completed\",\""+field+"\":\"https://cdn.example/done.mp4\"}";
      var result=ark.poll("platform-task",plan,"proxy-key");
      assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,result.status());assertEquals("https://cdn.example/done.mp4",result.outputs().getFirst().httpsUrl());
      assertEquals("GET /doubao/api/v3/contents/generations/tasks/platform-task",paths.getLast());
    }
    answer="{\"task_id\":\"another-task\",\"status\":\"completed\",\"video_url\":\"https://cdn.example/done.mp4\"}";
    assertThrows(DependencyUnavailableException.class,()->ark.poll("platform-task",plan,"proxy-key"));
    answer="{\"data\":{\"task_id\":\"platform-task\",\"status\":\"completed\",\"official_url\":\"https://cdn.example/done.mp4\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,ark.poll("platform-task",plan,"proxy-key").status());
    assertEquals("platform-task",ark.submitVideo(request(MediaType.VIDEO,1,"480p"),plan,"proxy-key"));
    answer="{\"task_id\":\"wrong\",\"data\":{\"task_id\":\"platform-task\",\"status\":\"completed\",\"official_url\":\"https://cdn.example/done.mp4\"}}";
    assertThrows(DependencyUnavailableException.class,()->ark.poll("platform-task",plan,"proxy-key"));
    answer="{\"task_id\":\"platform-task\",\"data\":{\"task_id\":\"another-task\",\"status\":\"completed\",\"official_url\":\"https://cdn.example/other.mp4\"}}";
    assertThrows(DependencyUnavailableException.class,()->ark.poll("platform-task",plan,"proxy-key"));
    answer="{\"upstream_task_id\":\"cgt-original\"}";
    assertThrows(DependencyUnavailableException.class,()->ark.submitVideo(request(MediaType.VIDEO,1,"480p"),plan,"proxy-key"));
  }
  @Test void documentedProfilesUseSupportedUpstreamParametersForSeedance25AndSeedream5Pro() {
    var http=new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2));
    var ark=new ArkMediaAdapter(http);
    var videoPlan=new ProviderSettings.Plan(endpoint,"doubao-seedance-2-5-260628",MediaType.VIDEO,"720p","720p","16:9",null,null,null,null,0);
    answer="{\"id\":\"task-profile-check\"}";
    ark.submitVideo(new TaskRequest(MediaType.VIDEO,"public-model","prompt","16:9","720p",1,4,
        List.of(new MediaReference(null,"https://cdn.example/first.png"),new MediaReference(null,"https://cdn.example/last.png"))),videoPlan,"test-key");
    var content=bodies.getLast().path("content");
    assertEquals("first_frame",content.get(1).path("role").asText());
    assertEquals("last_frame",content.get(2).path("role").asText());
    assertEquals("https://cdn.example/first.png",content.get(1).path("image_url").path("url").asText());
    assertEquals("https://cdn.example/last.png",content.get(2).path("image_url").path("url").asText());
    assertEquals("adaptive",bodies.getLast().path("ratio").asText());
    ark.submitVideo(new TaskRequest(MediaType.VIDEO,"public-model","prompt","16:9","720p",1,4,List.of()),videoPlan,"test-key");
    assertEquals("16:9",bodies.getLast().path("ratio").asText());
    answer="{\"data\":[{\"url\":\"https://cdn.example/profile.png\"}]}";
    var imagePlan=new ProviderSettings.Plan(endpoint,"doubao-seedream-5-0-pro-260628",MediaType.IMAGE,"2048x2048","2K","1:1",null,null,null,null,0);
    ark.image(new TaskRequest(MediaType.IMAGE,"public-model","prompt","1:1","2K",1,0,List.of()),imagePlan,"test-key");
    assertFalse(bodies.getLast().has("sequential_image_generation"));assertFalse(bodies.getLast().has("stream"));
    assertEquals("2048x2048",bodies.getLast().path("size").asText());
  }

  @Test void routingManagedModelAndSizeAreUsedWithOnlyDeploymentCredentials() {
    settings=ProviderSettings.from(Map.of("MEDIA_ARK_BASE_URL",endpoint,"MEDIA_ARK_PROVIDER_ID","ark","VOLC_ARK_API_KEY","fixture-ark-secret"),json);
    var route=new RouteSnapshot("managed","public-model","ark","Ark","https://metadata.example","new-model","{}","""
        {"type":"IMAGE","displayName":"Managed","resolutions":["2K"],"aspectRatios":["1:1"],
        "maxCount":1,"minDurationSeconds":0,"maxDurationSeconds":0,"sizes":{"2K":{"1:1":"2048x2048"}}}
        """,clock.instant());
    answer="{\"data\":[{\"url\":\"https://cdn.example/managed.png\"}]}";
    adapter().submit("managed","owner",request(MediaType.IMAGE,1,"2K"),route);
    assertEquals("new-model",bodies.getFirst().path("model").asText());
    assertEquals("2048x2048",bodies.getFirst().path("size").asText());
    assertEquals(1,posts.get());
    assertFalse(jdbc.queryForObject("select plan_json from media_provider_operation where operation_id='managed'",String.class).contains("fixture-ark-secret"));
  }
  @Test void seedreamPartialResultsAreDurableAndReplayNeverGeneratesAgain() {
    answer="{\"data\":[{\"url\":\"https://cdn.example/one.png\"},{\"error\":{\"code\":\"GenerationFailed\"}},{\"url\":\"https://cdn.example/three.png\"}]}";
    var r=request(MediaType.IMAGE,3,"2K"); var p=adapter();
    String handle=submit(p,"image",r);
    assertEquals(handle,submit(adapter(),"image",r));
    var result=adapter().poll(handle,r,route("image",r.type()));
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,result.status());
    assertEquals(List.of(0,2),result.outputs().stream().map(ProviderPort.ProviderOutput::index).toList());
    assertEquals(1,posts.get());
    assertEquals("POST /api/v3/images/generations",paths.getFirst());
    assertEquals("url",bodies.getFirst().path("response_format").asText());
    assertEquals("2048x2048",bodies.getFirst().path("size").asText());
    assertEquals("seedream-fixture",bodies.getFirst().path("model").asText());
    assertEquals(3,bodies.getFirst().path("sequential_image_generation_options").path("max_images").asInt());
    assertFalse(jdbc.queryForMap("select * from media_provider_operation").toString().contains("fixture-ark-secret"));
  }
  @Test void videoSubmissionPendingAndNativeResultSurviveAdapterRestart() {
    answer="{\"id\":\"upstream-video\"}";
    var r=request(MediaType.VIDEO,1,"480p"); String h=submit(adapter(),"video",r);
    answer="{\"id\":\"upstream-video\",\"status\":\"running\"}";
    assertEquals(ProviderPort.ProviderPoll.Status.PENDING,adapter().poll(h,r,route("video",r.type())).status());
    answer="{\"id\":\"upstream-video\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/video.mp4\"}}";
    assertEquals("https://cdn.example/video.mp4",adapter().poll(h,r,route("video",r.type())).outputs().getFirst().httpsUrl());
    assertEquals("GET /api/v3/contents/generations/tasks/upstream-video",paths.getLast());
    assertEquals("480p",bodies.getFirst().path("resolution").asText());
    assertEquals("16:9",bodies.getFirst().path("ratio").asText());
    assertEquals(1,posts.get());
  }
  @Test void ambiguousHttpTimeoutAndMalformedAcceptanceNeverResubmit() {
    var r=request(MediaType.IMAGE,1,"2K"); delay=150;
    assertThrows(DependencyUnavailableException.class,()->submit(adapter(Duration.ofMillis(30)),"timeout",r));
    delay=0;
    assertThrows(DependencyUnavailableException.class,()->submit(adapter(),"timeout",r));
    assertEquals(1,posts.get());
    answer="not-json";
    assertThrows(DependencyUnavailableException.class,()->submit(adapter(),"malformed",r));
    assertThrows(DependencyUnavailableException.class,()->submit(adapter(),"malformed",r));
    assertEquals(2,posts.get());
  }
  @Test void rejectsErrorsWithoutPersistingOrExposingProviderMessages() {
    status=400; answer="{\"error\":{\"code\":\"InvalidParameter\",\"message\":\"fixture-ark-secret https://private.example/signed\"}}";
    var r=request(MediaType.VIDEO,1,"480p");
    var error=assertThrows(DefinitiveRejectionException.class,()->submit(adapter(),"bad",r));
    assertEquals("PROVIDER_REJECTED",error.getMessage()); assertNull(error.getCause());
    assertThrows(DefinitiveRejectionException.class,()->submit(adapter(),"bad",r));
    assertEquals(1,posts.get());
    assertFalse(jdbc.queryForMap("select * from media_provider_operation").toString().contains("fixture-ark-secret"));
  }
  @Test void unsupportedModelsAndFileIdsAreRejectedBeforeChargeOrHttp() {
    var p=adapter(); var r=new TaskRequest(MediaType.IMAGE,"public-model","cloud","2K",1,0,List.of(new MediaReference("file-id",null)));
    assertThrows(DefinitiveRejectionException.class,()->p.requireAvailable(r,route("ref",MediaType.IMAGE)));
    assertThrows(DefinitiveRejectionException.class,()->submit(p,"ref",r));
    assertEquals(0,posts.get());
  }
  @Test void retainsNativeTaskAndUsageAfterEnhancementStarts() {
    var request=request(MediaType.VIDEO,1,"720p");
    answer="{\"id\":\"native-real-id\"}";
    String id=submit(adapter(),"diagnostic",request);
    answer="{\"status\":\"succeeded\",\"resolution\":\"480p\",\"usage\":{\"completion_tokens\":12345},\"content\":{\"video_url\":\"https://cdn.example/native.mp4\"}}";
    adapter().poll(id,request,route(id,request.type()));
    adapter().archiveNative(id,"owner",new StoragePort() {
      public List<MediaReference> resolveReferences(String owner,List<MediaReference> refs) { return refs; }
      public String archive(String owner,String task,int index,String source) { throw new AssertionError("stage required"); }
      public String archive(String owner,String task,int index,String source,ArchiveStage stage) {
        assertEquals(ArchiveStage.NATIVE,stage);
        assertEquals("owner",owner); assertEquals("https://cdn.example/native.mp4",source);
        return "native-storage-file";
      }
    });
    answer="{\"Response\":{\"TaskId\":\"enhance-real-id\"}}";
    adapter().poll(id,request,route(id,request.type()));
    var row=jdbc.queryForMap("SELECT * FROM media_provider_operation WHERE operation_id=?",id);
    assertEquals("native-real-id",row.get("generation_task_id"));
    assertEquals("enhance-real-id",row.get("enhancement_task_id"));
    assertEquals(12345L,((Number)row.get("video_tokens")).longValue());
    journal=new ProviderJournal(jdbc,json);
    var diagnostics=adapter().diagnostics(id);
    assertEquals("native-storage-file",diagnostics.generation().outputs().getFirst().fileId());
    assertEquals("native-real-id",diagnostics.generation().taskId());
    assertEquals("enhance-real-id",diagnostics.enhancement().taskId());
    assertNull(diagnostics.enhancement().scheduleId());assertEquals(720L,diagnostics.enhancement().definitionId());
    assertNotNull(diagnostics.generation().durationMs());assertNull(diagnostics.enhancement().durationMs());
    assertFalse(json.valueToTree(diagnostics).toString().contains("https://"));
    answer="{\"Response\":{\"Status\":\"FINISH\",\"WorkflowTask\":{\"ErrCode\":1001,\"ErrCodeExt\":\"MediaProcessFailed\"}}}";
    adapter().poll(id,request,route(id,request.type()));
    diagnostics=adapter().diagnostics(id);
    assertEquals("native-storage-file",diagnostics.generation().outputs().getFirst().fileId());
    assertEquals("MediaProcessFailed",diagnostics.enhancement().failureCode());
    assertNotNull(diagnostics.enhancement().durationMs());

  }
  @Test void migrationRetainsExistingRunningReceiptsOnSuccessAndFailure() {
    for(boolean enhanced:List.of(false,true)) for(boolean failed:List.of(false,true)) {
      var request=request(MediaType.VIDEO,1,enhanced?"720p":"480p");String id=UUID.randomUUID().toString();
      answer="{\"id\":\"legacy-native-id\"}";submit(adapter(),id,request);
      if(enhanced) {
        answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
        adapter().poll(id,request,route(id,request.type()));
        answer="{\"Response\":{\"TaskId\":\"legacy-mps-id\"}}";adapter().poll(id,request,route(id,request.type()));
      }
      jdbc.update("UPDATE media_provider_operation SET generation_task_id=NULL,enhancement_task_id=NULL WHERE operation_id=?",id);
      var old=journal.find(id);
      if(failed) journal.fail(old,"ExistingFailure");else journal.move(old,"COMPLETE",null,null,List.of());
      var diagnostic=adapter().diagnostics(id);
      assertEquals(enhanced?"legacy-mps-id":"legacy-native-id",enhanced?diagnostic.enhancement().taskId():diagnostic.generation().taskId());
    }
  }
  @Test void absentOrInvalidUsageRemainsUnrecordedAndHistoryIsNotBackfilled() {
    for(String usage:List.of("{}","{\"completion_tokens\":null}","{\"completion_tokens\":-1}","{\"completion_tokens\":1.5}","{\"completion_tokens\":\"123\"}")) {
      var request=request(MediaType.VIDEO,1,"480p");String id=UUID.randomUUID().toString();
      answer="{\"id\":\"native-id\"}";submit(adapter(),id,request);
      answer="{\"status\":\"succeeded\",\"usage\":"+usage+",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
      adapter().poll(id,request,route(id,request.type()));
      assertNull(adapter().diagnostics(id).generation().videoTokens());
      assertNull(adapter().diagnostics(id).enhancement());
      jdbc.update("UPDATE media_provider_operation SET generation_task_id=NULL,generation_started_at=NULL,generation_finished_at=NULL WHERE operation_id=?",id);
      var historical=adapter().diagnostics(id).generation();
      assertNull(historical.taskId());assertNull(historical.durationMs());
    }
  }
  @Test void enhancementUsesExistingTargetPlanAndOnlyPublishesVerifiedFinalOutput() {
    answer="{\"id\":\"source-video\"}";
    var r=request(MediaType.VIDEO,1,"720p"); var p=adapter(); String h=submit(p,"enhance",r);
    assertEquals("480p",bodies.getFirst().path("resolution").asText());
    assertEquals("16:9",bodies.getFirst().path("ratio").asText());
    answer="{\"id\":\"source-video\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    assertTrue(p.poll(h,r,route("enhance",r.type())).outputs().isEmpty());
    answer="{\"Response\":{\"TaskId\":\"mps-task\"}}";
    assertTrue(adapter().poll(h,r,route("enhance",r.type())).outputs().isEmpty());
    assertEquals("enhance",bodies.getLast().path("SessionId").asText());
    assertEquals(720,bodies.getLast().path("MediaProcessTask").path("TranscodeTaskSet").get(0).path("Definition").asInt());
    answer="{\"Response\":{\"Status\":\"FINISH\",\"WorkflowTask\":{\"MediaProcessResultSet\":[{\"TranscodeTask\":{\"Status\":\"SUCCESS\",\"Output\":{\"Definition\":720,\"Width\":1280,\"Height\":720,\"Path\":\"/output/final.mp4\",\"OutputStorage\":{\"Type\":\"COS\",\"CosOutputStorage\":{\"Bucket\":\"fixture-123\",\"Region\":\"ap-guangzhou\"}}}}}]}}}";
    var result=adapter().poll(h,r,route("enhance",r.type()));
    assertEquals(1,result.outputs().size()); assertTrue(result.outputs().getFirst().httpsUrl().startsWith("https://fixture-123.cos.ap-guangzhou.myqcloud.com/output/final.mp4"));
    assertEquals(3,posts.get()); // one generation, ProcessMedia and DescribeTaskDetail
    assertEquals("DescribeTaskDetail",headers.getLast().get("X-tc-action").getFirst());
    assertTrue(headers.getLast().get("Authorization").getFirst().startsWith("TC3-HMAC-SHA256 Credential=fixture-id/2026-09-08/mps/tc3_request,"));
    String source=result.outputs().getFirst().httpsUrl();
    assertFalse(source.contains("fixture-id"));
    assertFalse(jdbc.queryForMap("select * from media_provider_operation").toString().contains("fixture-id"));
    var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(1)),clock);
    assertTrue(mps.archiveUrl(source,settings).contains("q-signature="));
  }
  @Test void savedMpsConfigurationIsLiveButQueuedPlansKeepTheirTemplateAndDirectory() {
    var config=new java.util.concurrent.atomic.AtomicReference<>(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration(
        1,"saved-id","saved-key","ap-beijing","saved-123","ap-guangzhou","enhance_output/",101,102,103));
    var provider=dynamicAdapter(()->settings.withMpsConfiguration(config.get()));
    var request=request(MediaType.VIDEO,1,"720p");var route=route("saved-mps",MediaType.VIDEO);
    provider.requireAvailable(request,route);
    assertEquals(101,journal.find(route.taskId()).plan().template());
    assertEquals("480p",journal.find(route.taskId()).plan().sourceResolution());
    config.set(new com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration(
        2,"saved-id","rotated-key","ap-shanghai","changed-123","ap-shanghai","new_output/",201,202,203));
    answer="{\"id\":\"original-video\"}";provider.submit(route.taskId(),"owner",request,route);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    provider.poll(route.taskId(),request,route);
    answer="{\"Response\":{\"TaskId\":\"enhanced-video\"}}";
    provider.poll(route.taskId(),request,route);
    assertEquals(101,bodies.getLast().path("MediaProcessTask").path("TranscodeTaskSet").get(0).path("Definition").asLong());
    assertEquals("/enhance_output/saved-mps/",bodies.getLast().path("OutputDir").asText());
    assertEquals("ap-guangzhou",bodies.getLast().path("OutputStorage").path("CosOutputStorage").path("Region").asText());
    assertEquals("ap-beijing",headers.getLast().get("X-tc-region").getFirst());
    assertTrue(headers.getLast().get("Authorization").getFirst().contains("Credential=saved-id/"));
    var next=route("next-mps",MediaType.VIDEO);provider.requireAvailable(request,next);
    assertEquals(201,journal.find(next.taskId()).plan().template());
    assertEquals("/new_output/",journal.find(next.taskId()).plan().outputDir());
    var signed=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(1)),clock)
        .archiveUrl("https://saved-123.cos.ap-guangzhou.myqcloud.com/enhance_output/result.mp4",settings.withMpsConfiguration(config.get()),journal.executionPlan(route.taskId()));
    assertTrue(signed.contains("q-signature="));
  }
  @Test void preservesFailureStageAndSafeSupplierCodeAcrossRestart() {
    var r=request(MediaType.VIDEO,1,"720p"); var p=adapter();
    answer="{\"id\":\"source-video\"}"; String h=submit(p,"failed-enhance",r);
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    p.poll(h,r,route(h,r.type()));
    answer="{\"Response\":{\"TaskId\":\"mps-task\"}}"; p.poll(h,r,route(h,r.type()));
    answer="{\"Response\":{\"Status\":\"FINISH\",\"WorkflowTask\":{\"ErrCode\":1001,\"ErrCodeExt\":\"MediaProcessFailed\",\"Message\":\"fixture-tencent-secret\"}}}";
    assertEquals(ProviderPort.ProviderPoll.Status.FAILED,p.poll(h,r,route(h,r.type())).status());
    var detail=adapter().failure(h);
    assertEquals("MPS_RUNNING",detail.stage()); assertEquals("MediaProcessFailed",detail.code());
    assertEquals("火山生成失败",MediaFailureMessages.message("PROVIDER_FAILED",detail));
    assertFalse(jdbc.queryForList("select * from media_provider_operation").toString().contains("fixture-tencent-secret"));
    assertEquals(ProviderPort.ProviderPoll.Status.FAILED,adapter().poll(h,r,route(h,r.type())).status());
    assertEquals(detail,adapter().failure(h));
  }
  @Test void mapsKnownReasonsWithoutLeakingUnknownProviderText() {
    assertEquals("内容审核未通过，请调整提示词或素材后重试",MediaFailureMessages.message("PROVIDER_FAILED",new ProviderPort.FailureDetail("ARK_RUNNING","OutputAudioSensitiveContentDetected.PolicyViolation")));
    assertEquals("生成超时，请稍后重试",MediaFailureMessages.message("GENERATION_TIMEOUT",null));
    assertEquals("生成超时，请稍后重试",MediaFailureMessages.message("PROVIDER_FAILED",new ProviderPort.FailureDetail("ARK_RUNNING","ActualSupplierCode","expired")));
    assertEquals("素材不符合要求，请检查格式、尺寸和内容后重试",MediaFailureMessages.message("PROVIDER_FAILED",new ProviderPort.FailureDetail("ARK_RUNNING","InvalidParameter.ImageSize")));
    assertEquals("生成失败，请稍后重试",MediaFailureMessages.message("PROVIDER_FAILED",new ProviderPort.FailureDetail("ARK_RUNNING","https://internal.example/secret")));
  }
  @Test void terminalVideoErrorsAndInvalidOutputsCannotBecomeSuccess() {
    var r=request(MediaType.VIDEO,1,"480p");
    for(String state:List.of("failed","expired","cancelled")) {
      answer="{\"id\":\"upstream\"}"; String h=submit(adapter(),state,r);
      answer="{\"status\":\""+state+"\",\"error\":{\"code\":\"ActualSupplierCode\",\"message\":\"fixture-ark-secret\"}}";
      assertEquals(ProviderPort.ProviderPoll.Status.FAILED,adapter().poll(h,r,route(state,r.type())).status());
      assertEquals("ActualSupplierCode",adapter().failure(h).code());
      assertEquals(state,adapter().failure(h).status());
      if(state.equals("expired")) assertEquals("生成超时，请稍后重试",MediaFailureMessages.message("PROVIDER_FAILED",adapter().failure(h)));
    }
    var image=request(MediaType.IMAGE,2,"2K");
    answer="{\"data\":[{\"b64_json\":\"AAAA\"},{\"url\":\"data:image/png;base64,AAAA\"}]}";
    String h=submit(adapter(),"unsafe",image);
    assertTrue(adapter().poll(h,image,route(h,image.type())).outputs().isEmpty());
    assertFalse(jdbc.queryForList("select * from media_provider_operation").toString().contains("AAAA"));
  }
  @Test void serverErrorIsAmbiguousAndRedirectNeverForwardsCredentials() {
    var r=request(MediaType.IMAGE,1,"2K");
    for(int code:List.of(408,409,429,500,503,302)) {
      status=code; answer="{\"error\":{\"message\":\"fixture-ark-secret\"}}";
      assertThrows(DependencyUnavailableException.class,()->submit(adapter(),"status-"+code,r));
      assertThrows(DependencyUnavailableException.class,()->submit(adapter(),"status-"+code,r));
    }
    assertEquals(6,posts.get());
  }
  @Test void concurrentSubmissionsHaveOneDurableSendWinner() throws Exception {
    answer="{\"id\":\"upstream\"}"; var r=request(MediaType.VIDEO,1,"480p");
    try(var pool=Executors.newFixedThreadPool(4)) {
      List<Future<?>> attempts=new ArrayList<>();
      for(int i=0;i<8;i++) attempts.add(pool.submit(()->{
        try { submit(adapter(),"concurrent",r); } catch(DependencyUnavailableException inFlight) { }
      }));
      for(var attempt:attempts) attempt.get();
    }
    assertEquals(1,posts.get()); assertEquals("ARK_RUNNING",journal.find("concurrent").phase());
  }
  @Test void fileReferencesAndWrongDurationCannotCreatePaidOperations() {
    var p=adapter(); var r=new TaskRequest(MediaType.VIDEO,"public-model","cloud","480p",1,60,List.of());
    assertThrows(DefinitiveRejectionException.class,()->p.requireAvailable(r,route("duration",r.type())));
    var unknown=new RouteSnapshot("unknown","public-model","another-provider","Other","https://other.example","seedance-fixture","{}","{}",clock.instant());
    assertThrows(DefinitiveRejectionException.class,()->p.requireAvailable(request(MediaType.VIDEO,1,"480p"),unknown));
    assertEquals(0,jdbc.queryForObject("select count(*) from media_provider_operation",Integer.class));
    assertEquals(0,posts.get());
  }
  @Test void enhancementFailureAndUnexpectedDimensionsFailWithoutPublishingTheSource() {
    var r=request(MediaType.VIDEO,1,"1080p");
    for(String output:List.of(
        "{\"Status\":\"FAIL\",\"Input\":{\"Definition\":1080},\"Message\":\"fixture-tencent-secret\"}",
        "{\"Status\":\"SUCCESS\",\"Output\":{\"Definition\":1080,\"Width\":1280,\"Height\":720}}")) {
      String id=UUID.randomUUID().toString(); answer="{\"id\":\"source\"}"; String h=submit(adapter(),id,r);
      assertEquals("720p",bodies.getLast().path("resolution").asText());
      answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
      adapter().poll(h,r,route(id,r.type()));
      answer="{\"Response\":{\"TaskId\":\"mps-task\"}}"; adapter().poll(h,r,route(id,r.type()));
      answer="{\"Response\":{\"Status\":\"FINISH\",\"WorkflowTask\":{\"MediaProcessResultSet\":[{\"TranscodeTask\":"+output+"}]}}}";
      var poll=adapter().poll(h,r,route(id,r.type()));
      assertEquals(ProviderPort.ProviderPoll.Status.FAILED,poll.status()); assertTrue(poll.outputs().isEmpty());
    }
  }
  @Test void mpsUncertainSubmissionDoesNotResubmitAndTaskPlanIsFrozen() {
    var r=request(MediaType.VIDEO,1,"4K"); answer="{\"id\":\"source\"}"; String h=submit(adapter(),"mps-uncertain",r);
    assertEquals("1080p",bodies.getFirst().path("resolution").asText());
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/source.mp4\"}}";
    adapter().poll(h,r,route(h,r.type()));
    answer="{\"Response\":{\"Error\":{\"Code\":\"FailedOperation.DuplicatedSessionId\",\"Message\":\"fixture-tencent-secret\"}}}";
    assertThrows(DependencyUnavailableException.class,()->adapter().poll(h,r,route(h,r.type())));
    assertEquals(ProviderPort.ProviderPoll.Status.PENDING,adapter().poll(h,r,route(h,r.type())).status());
    assertEquals(2,posts.get()); assertEquals(2160,journal.find(h).plan().template());
    assertEquals("MPS_SENDING",journal.find(h).phase());
  }

  @Configuration @EnableAutoConfiguration @MapperScan("com.xzwl.cloud.media.infrastructure.task")
  @Import(MyBatisMediaTaskStore.class) static class WorkflowConfig {}
  static class MutableClock extends Clock {
    Instant now=Instant.parse("2026-09-08T00:00:00Z");
    public Instant instant() { return now; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    void advance(long seconds) { now=now.plusSeconds(seconds); }
  }
  org.springframework.context.ConfigurableApplicationContext workflowContext() {
    return new SpringApplicationBuilder(WorkflowConfig.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url=jdbc:h2:file:"+directory.resolve("workflow")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "--spring.datasource.username=sa","--spring.datasource.password=",
        "--spring.flyway.locations=classpath:db/media-migration/common","--spring.main.banner-mode=off","--logging.level.root=WARN");
  }
  @Test void asyncImageWorkerArchivesAndRefundsFailuresOrUncertainTimeouts() throws Exception {
    var mutable=new MutableClock();clock=mutable;
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","worker-async-key"));
    try(var app=workflowContext()) {
      jdbc=app.getBean(JdbcTemplate.class);journal=new ProviderJournal(jdbc,json);
      var store=app.getBean(MediaTaskStore.class);
      var policy=new TaskPolicy(Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
      var service=new MediaTaskService(store,clock,policy);
      Map<String,ChargeReceipt> charges=new HashMap<>();Map<String,BigDecimal> refunds=new HashMap<>();
      BillingPort billing=new BillingPort() {
        public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) {
          return charges.computeIfAbsent(id,k->new ChargeReceipt(k,new BigDecimal("1.2345"),BigDecimal.TEN,new BigDecimal("12.3456")));
        }
        public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) { refunds.putIfAbsent(id,points); }
      };
      var request=new TaskRequest(MediaType.IMAGE,"public-model","async workflow","2K",1,0,List.of());
      for(String outcome:List.of("complete","failed","uncertain")) {
        var task=service.submit("owner",outcome,request);var route=zexRoute(task.id(),"nano_banana_pro-2K","IMAGE");
        var worker=new MediaTaskWorker(store,(id,model)->route,billing,adapter(),new HttpStoragePort(RestClient.builder().baseUrl(endpoint).build()),clock,policy);
        worker.tick();worker.tick();
        answer=outcome.equals("uncertain")?"invalid acknowledgement":"{\"id\":\"image-worker-"+outcome+"\",\"status\":\"queued\"}";
        worker.tick();
        int submits=(int)paths.stream().filter(path->path.equals("POST /v1/videos")).count();
        journal=new ProviderJournal(jdbc,json);
        worker=new MediaTaskWorker(store,(id,model)->{throw new AssertionError("must use original persisted route");},billing,adapter(),new HttpStoragePort(RestClient.builder().baseUrl(endpoint).build()),clock,policy);
        if(outcome.equals("uncertain")) mutable.advance(61);
        answer=outcome.equals("complete")?"{\"status\":\"completed\",\"metadata\":{\"image_url\":\"https://cdn.example/async.png\"}}"
            :"{\"status\":\"failed\",\"error\":{\"message\":\"private-provider-detail\"}}";
        for(int i=0;i<8;i++){mutable.advance(2);worker.tick();}
        assertEquals(outcome.equals("complete")?TaskStatus.SUCCEEDED:outcome.equals("uncertain")?TaskStatus.TIMED_OUT:TaskStatus.FAILED,service.get("owner",task.id()).status());
        assertEquals(submits,paths.stream().filter(path->path.equals("POST /v1/videos")).count());
        if(outcome.equals("complete")) {
          assertEquals("archived-file",service.results("owner",task.id()).getFirst().fileId());
          assertNull(service.results("owner",task.id()).getFirst().sourceUrl());assertFalse(refunds.containsKey(task.id()+"-refund"));
        } else assertEquals(new BigDecimal("12.3456"),refunds.get(task.id()+"-refund"));
      }
      assertEquals(3,charges.size());assertEquals(2,refunds.size());
      assertEquals(3,paths.stream().filter(path->path.equals("POST /v1/videos")).count());
      assertFalse(jdbc.queryForList("SELECT * FROM media_provider_operation").toString().contains("worker-async-key"));
    }
  }
  @Test void existingSynchronousReceiptRemainsReadableWithoutOfferingNewSyncTasks() {
    settings=settings.withConnection(new Connection(1,"zex",endpoint+"/v1","legacy-key"));
    var request=new TaskRequest(MediaType.IMAGE,"public-model","old task","2K",1,0,List.of());
    var route=new RouteSnapshot("legacy-sync","public-model","zex","ZEX","https://zexapi.com/v1","gpt-image2","{}","{}",clock.instant());
    var plan=new ProviderSettings.Plan(endpoint+"/v1","gpt-image2",MediaType.IMAGE,"2048x2048","2K","1:1",null,null,null,null,0);
    var op=journal.create(route.taskId(),plan);
    journal.move(op,"COMPLETE",null,null,List.of(new ProviderPort.ProviderOutput(0,"https://cdn.example/old.png")));
    assertEquals("https://cdn.example/old.png",adapter().poll(route.taskId(),request,route).outputs().getFirst().httpsUrl());
    assertThrows(DefinitiveRejectionException.class,()->settings.plan(request,route));assertEquals(0,posts.get());
  }

  @Test void realWorkerArchivesPartialOutputsAndRetriesRefundWithOriginalAmount() {
    var mutable=new MutableClock(); clock=mutable;
    try(var app=workflowContext()) {
      jdbc=app.getBean(JdbcTemplate.class); journal=new ProviderJournal(jdbc,json);
      var store=app.getBean(MediaTaskStore.class);
      var policy=new TaskPolicy(Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
      var service=new MediaTaskService(store,clock,policy);
      var request=request(MediaType.IMAGE,3,"2K"); var task=service.submit("owner","partial",request);
      AtomicInteger charges=new AtomicInteger(),refundAttempts=new AtomicInteger();
      Map<String,BigDecimal> refunds=new HashMap<>();
      BillingPort billing=new BillingPort() {
        public ChargeReceipt charge(String id,String owner,TaskRequest req,RouteSnapshot route) {
          charges.incrementAndGet(); return new ChargeReceipt(id,new BigDecimal("1.2345"),BigDecimal.TEN,new BigDecimal("12.3456"));
        }
        public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) {
          refunds.putIfAbsent(id,points);
          if(refundAttempts.getAndIncrement()==0) throw new DependencyUnavailableException("fixture refund acknowledgement lost");
        }
      };
      StoragePort archive=new HttpStoragePort(RestClient.builder().baseUrl(endpoint).build());
      var worker=new MediaTaskWorker(store,(id,model)->route(id,request.type()),billing,adapter(),archive,clock,policy);
      answer="{\"data\":[{\"url\":\"https://cdn.example/one.png\"},{\"error\":{\"code\":\"GenerationFailed\"}},{\"url\":\"https://cdn.example/three.png\"}]}";
      for(int i=0;i<6 && service.get("owner",task.id()).status()!=TaskStatus.ARCHIVING;i++) { worker.tick(); mutable.advance(2); }
      assertEquals(TaskStatus.ARCHIVING,service.get("owner",task.id()).status());
      assertTrue(service.results("owner",task.id()).isEmpty());
      for(int i=0;i<8;i++) { worker.tick(); mutable.advance(2); }
      assertEquals(TaskStatus.PARTIAL_SUCCESS,service.get("owner",task.id()).status());
      assertEquals(2,service.results("owner",task.id()).size());
      assertTrue(service.results("owner",task.id()).stream().allMatch(r->r.sourceUrl()==null && "archived-file".equals(r.fileId())));
      assertEquals(Map.of(task.id()+"-refund",new BigDecimal("4.1152")),refunds);
      assertEquals(1,charges.get()); assertEquals(2,refundAttempts.get());
      assertEquals(1,paths.stream().filter(p->p.equals("POST /api/v3/images/generations")).count());
      assertEquals(2,paths.stream().filter(p->p.equals("POST /internal/v1/files/media-results/archive-url")).count());
      assertEquals(task.id(),service.submit("owner","partial",request).id());
    }
  }
  @Test void applicationRestartRecoversAcceptedJournalAndUncertainSubmitEventuallyRefunds() {
    var mutable=new MutableClock(); clock=mutable;
    var policy=new TaskPolicy(Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
    Map<String,ChargeReceipt> charges=new HashMap<>(); Map<String,BigDecimal> refunds=new HashMap<>();
    BillingPort billing=new BillingPort() {
      public ChargeReceipt charge(String id,String owner,TaskRequest req,RouteSnapshot route) {
        return charges.computeIfAbsent(id,key->new ChargeReceipt(key,new BigDecimal("1.2345"),BigDecimal.TEN,new BigDecimal("12.3456")));
      }
      public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) { refunds.putIfAbsent(id,points); }
    };
    TaskRequest request=request(MediaType.IMAGE,1,"2K"); String accepted,uncertain;
    try(var first=workflowContext()) {
      jdbc=first.getBean(JdbcTemplate.class); journal=new ProviderJournal(jdbc,json);
      var store=first.getBean(MediaTaskStore.class); var service=new MediaTaskService(store,clock,policy);
      accepted=service.submit("owner","accepted",request).id();
      var worker=new MediaTaskWorker(store,(id,model)->route(id,request.type()),billing,adapter(),new StoragePort() {
      public java.util.List<MediaReference> resolveReferences(String owner, java.util.List<MediaReference> references) { return references; }
      public String archive(String owner,String id,int index,String url) { return "file"; }
    },clock,policy);
      worker.tick(); worker.tick(); // charge, but leave SUBMIT_PENDING
      answer="{\"data\":[{\"url\":\"https://cdn.example/image.png\"}]}";
      submit(adapter(),accepted,request); // accepted provider receipt, process dies before saving RUNNING
      store.claim(accepted,"crashed",mutable.instant(),mutable.instant().plusSeconds(30)).orElseThrow();
      uncertain=service.submit("owner","uncertain",request).id();
      worker.tick(); worker.tick();
      answer="invalid accepted response"; worker.tick();
      assertEquals("ARK_SENDING",journal.find(uncertain).phase());
    }
    try(var second=workflowContext()) {
      settings=ProviderSettings.from(Map.of(),json); // cached receipts must recover even when credentials are absent
      jdbc=second.getBean(JdbcTemplate.class); journal=new ProviderJournal(jdbc,json);
      var store=second.getBean(MediaTaskStore.class); var service=new MediaTaskService(store,clock,policy);
      mutable.advance(31);
      var worker=new MediaTaskWorker(store,(id,model)->{throw new AssertionError("must use persisted route");},billing,adapter(),new StoragePort() {
      public java.util.List<MediaReference> resolveReferences(String owner, java.util.List<MediaReference> references) { return references; }
      public String archive(String owner,String id,int index,String url) { return "file"; }
    },clock,policy);
      for(int i=0;i<5;i++) { worker.tick(); mutable.advance(2); }
      assertEquals(TaskStatus.SUCCEEDED,service.get("owner",accepted).status());
      assertTrue(refunds.isEmpty());
      mutable.advance(60); worker.tick(); worker.tick();
      assertEquals(TaskStatus.TIMED_OUT,service.get("owner",uncertain).status());
      assertEquals(Map.of(uncertain+"-refund",new BigDecimal("12.3456")),refunds);
      assertEquals(2,charges.size()); assertEquals(2,posts.get());
      assertTrue(service.results("owner",uncertain).isEmpty());
    }
  }

  @Test void unsupportedAspectRatioIsRejectedBeforeBillingAndProviderHttp() {
    try(var app=workflowContext()) {
      jdbc=app.getBean(JdbcTemplate.class); journal=new ProviderJournal(jdbc,json);
      var store=app.getBean(MediaTaskStore.class);
      var policy=new TaskPolicy(Duration.ofMinutes(1),Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
      var service=new MediaTaskService(store,clock,policy);
      var request=new TaskRequest(MediaType.IMAGE,"public-model","cloud","9:16","2K",1,0,List.of());
      var task=service.submit("owner","ratio-rejected",request);
      var charges=new AtomicInteger();
      BillingPort billing=new BillingPort() {
        public ChargeReceipt charge(String id,String owner,TaskRequest r,RouteSnapshot route) { charges.incrementAndGet(); throw new AssertionError("must reject before charge"); }
        public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) { throw new AssertionError("nothing to refund"); }
      };
      var worker=new MediaTaskWorker(store,(id,model)->route(id,request.type()),billing,adapter(),
          new HttpStoragePort(RestClient.builder().baseUrl(endpoint).build()),clock,policy);
      worker.tick(); worker.tick();
      assertEquals(TaskStatus.FAILED,service.get("owner",task.id()).status());
      assertNull(service.get("owner",task.id()).data().charge());
      assertEquals(0,charges.get()); assertEquals(0,posts.get());
    }
  }

  @Test void missingResolutionRatioMappingCannotCreateAProviderOperation() {
    var request=new TaskRequest(MediaType.IMAGE,"public-model","cloud","16:9","4K",1,0,List.of());
    assertThrows(DefinitiveRejectionException.class,() -> adapter().requireAvailable(request,route("missing-size",request.type())));
    assertNull(journal.find("missing-size")); assertEquals(0,posts.get());
  }

  @Test void exactImageSizeAndHttpsReferencesReachArkWithoutEmbeddedMedia() {
    answer="{\"data\":[{\"url\":\"https://cdn.example/output.png\"}]}";
    var request=new TaskRequest(MediaType.IMAGE,"public-model","cloud","16:9","2K",1,0,
        List.of(new MediaReference(null,"https://storage.example/reference")));
    submit(adapter(),"wide",request);
    assertEquals("2560x1440",bodies.getFirst().path("size").asText());
    assertEquals("https://storage.example/reference",bodies.getFirst().path("image").get(0).asText());
    assertFalse(bodies.getFirst().toString().toLowerCase(Locale.ROOT).contains("base64"));
    assertEquals("16:9",journal.find("wide").plan().aspectRatio());
  }

  ProductionProviderPort dynamicAdapter(Supplier<ProviderSettings> source) {
    var http=new ProviderHttp(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(),json,Duration.ofSeconds(2));
    return new ProductionProviderPort(source,journal,new ArkMediaAdapter(http),new TencentMpsAdapter(http,clock));
  }

  @Test void savedConnectionRotationReachesRealHttpUsingOneSnapshotPerCallWithoutSecretInJournal() {
    AtomicReference<Connection> saved=new AtomicReference<>(settings.arkConnection());
    AtomicInteger reads=new AtomicInteger();
    var provider=dynamicAdapter(()-> { reads.incrementAndGet(); return settings.withConnection(saved.get()); });
    var request=request(MediaType.VIDEO,1,"480p"); var route=route("rotation",request.type());
    provider.requireAvailable(route); assertEquals(1,reads.get());
    provider.requireAvailable(request,route); assertEquals(2,reads.get());
    saved.set(new Connection(1,"ark",endpoint+"/api/v3","saved-submit-key"));
    answer="{\"id\":\"rotated-upstream\"}";
    provider.submit("rotation","owner",request,route); assertEquals(3,reads.get());
    assertEquals("Bearer saved-submit-key",headers.getLast().get("Authorization").getFirst());
    saved.set(new Connection(2,"ark",endpoint+"/api/v3/","saved-poll-key"));
    answer="{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/rotation.mp4\"}}";
    assertEquals(ProviderPort.ProviderPoll.Status.COMPLETED,provider.poll("rotation",request,route).status());
    assertEquals(4,reads.get());
    assertEquals("Bearer saved-poll-key",headers.getLast().get("Authorization").getFirst());
    String persisted=jdbc.queryForList("SELECT * FROM media_provider_operation").toString();
    for(String key:List.of("fixture-ark-secret","saved-submit-key","saved-poll-key")) assertFalse(persisted.contains(key));
    assertEquals("fixture-ark-secret",settings.arkConnection().apiKey());
    assertEquals("ark",settings.arkConnection().providerId()); assertEquals(0,settings.arkConnection().version());
  }

  @Test void changedBaseOrProviderCannotSendSavedKeyToAnExistingPaidPlan() {
    AtomicReference<ProviderSettings> current=new AtomicReference<>(settings);
    var provider=dynamicAdapter(current::get);
    var request=request(MediaType.VIDEO,1,"480p"); var route=route("frozen-target",request.type());
    provider.requireAvailable(request,route);
    current.set(settings.withConnection(new Connection(1,"ark","https://other.example/api/v3","other-key")));
    assertThrows(DependencyUnavailableException.class,()->provider.submit("frozen-target","owner",request,route));
    assertEquals("ARK_READY",journal.find("frozen-target").phase()); assertEquals(0,posts.get());
    current.set(settings.withConnection(new Connection(2,"different-provider",endpoint,"other-key")));
    assertThrows(DefinitiveRejectionException.class,()->provider.submit("frozen-target","owner",request,route));
    assertEquals(0,posts.get());
    current.set(settings); answer="{\"id\":\"frozen-upstream\"}";
    provider.submit("frozen-target","owner",request,route); assertEquals(1,posts.get());
    int requests=paths.size();
    current.set(settings.withConnection(new Connection(3,"ark","https://other.example/api/v3","other-key")));
    assertThrows(DependencyUnavailableException.class,()->provider.poll("frozen-target",request,route));
    current.set(settings.withConnection(new Connection(4,"different-provider",endpoint,"other-key")));
    assertThrows(DefinitiveRejectionException.class,()->provider.poll("frozen-target",request,route));
    assertEquals(requests,paths.size());
    assertEquals(endpoint,journal.find("frozen-target").plan().arkBase());
    assertEquals("ARK_RUNNING",journal.find("frozen-target").phase());
  }

  @Test void connectionCopyPreservesFrozenModelsAndMpsAndChecksRotatedSecretInOutputs() {
    var copy=settings.withConnection(new Connection(1,"ark",endpoint,"new-output-secret"));
    assertEquals(settings.plan(request(MediaType.VIDEO,1,"720p"),route("copy",MediaType.VIDEO)),
        copy.plan(request(MediaType.VIDEO,1,"720p"),route("copy",MediaType.VIDEO)));
    assertEquals(settings.secretId,copy.secretId); assertEquals(settings.secretKey,copy.secretKey);
    answer="{\"data\":[{\"url\":\"https://cdn.example/new-output-secret.png\"}]}";
    assertThrows(DefinitiveRejectionException.class,()->submit(dynamicAdapter(()->copy),"secret-output",request(MediaType.IMAGE,1,"2K")));
    assertFalse(jdbc.queryForList("SELECT * FROM media_provider_operation").toString().contains("new-output-secret"));
  }

  @Test void tc3AndCosSignaturesMatchIndependentFixedVectors() {
    // Fixed vectors computed independently with Python hashlib/hmac from Tencent's signing specifications.
    assertEquals("TC3-HMAC-SHA256 Credential=fixture-id/2026-09-08/mps/tc3_request, SignedHeaders=content-type;host;x-tc-action, Signature=3055e0eba9ff8df16a58407e3facbfd1f535f5eb6031715aec2872a604728517",
        TencentMpsAdapter.authorization("mps.tencentcloudapi.com","DescribeTaskDetail","{\"TaskId\":\"example-task\"}",1788825600,"fixture-id","fixture-tencent-secret"));
    var mps=new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(1)),clock);
    assertTrue(mps.archiveUrl("https://fixture-123.cos.ap-guangzhou.myqcloud.com/output/final.mp4",settings)
        .endsWith("q-signature=6674d6f3d7f3982a6784be412491a3ad0ef367f4"));
    assertEquals("https://unrelated.example/object",mps.archiveUrl("https://unrelated.example/object",settings));
  }

}
