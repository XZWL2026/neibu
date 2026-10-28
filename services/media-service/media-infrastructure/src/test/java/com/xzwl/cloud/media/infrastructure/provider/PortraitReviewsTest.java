package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PortraitReviewsTest {
  static final String MODEL="doubao-seedance-2-5-260628";
  final ObjectMapper json=new ObjectMapper();
  final Clock clock=Clock.fixed(Instant.parse("2026-09-10T12:30:00Z"),ZoneOffset.UTC);
  final List<String> actions=new CopyOnWriteArrayList<>();
  final List<JsonNode> bodies=new CopyOnWriteArrayList<>();
  final List<Map<String,List<String>>> headers=new CopyOnWriteArrayList<>();
  HttpServer server; JdbcTemplate jdbc; Map<String,String> env; ArkConnectionStore connections;
  RoutingPort routing; StoragePort storage; ProviderHttp http; JdbcPortraitReviews reviews;
  volatile CountDownLatch lateActiveArrived,releaseLateActive; final java.util.concurrent.atomic.AtomicInteger racingPolls=new java.util.concurrent.atomic.AtomicInteger();
  MediaReference.Kind storedKind=MediaReference.Kind.IMAGE;
  Double storedDuration() { return storedKind==MediaReference.Kind.IMAGE ? null : 3.25; }
  MediaReference resolvedPortrait() { return new MediaReference(null,"https://cdn.example/portrait",storedKind,storedDuration()); }
  volatile String maasPollResponse; final List<String> queries=new CopyOnWriteArrayList<>();
  volatile String assetStatus="Processing"; volatile String pollResponse; volatile String failedAction; volatile boolean uncertain;
  @BeforeEach void setup() throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/",e -> {
      if(e.getRequestURI().getPath().startsWith("/v1/private-avatar/")) {
        queries.add(Objects.toString(e.getRequestURI().getRawQuery(),""));
        String path=e.getRequestURI().getPath();actions.add("maas:"+e.getRequestMethod()+" "+path);
        headers.add(Map.copyOf(e.getRequestHeaders()));
        if(e.getRequestMethod().equals("POST")) bodies.add(json.readTree(e.getRequestBody()));
        String answer=path.endsWith("/groups") ? "{\"Result\":{\"Id\":\"maas-group\"}}"
            : "{\"Result\":{\"Id\":\"maas-asset\",\"GroupId\":\"maas-group\",\"Status\":\""+assetStatus+"\"}}";
        if(e.getRequestMethod().equals("GET") && maasPollResponse!=null) answer=maasPollResponse;
        byte[] bytes=answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();return;
      }
      if(e.getRequestURI().getPath().startsWith("/doubao/api/v3/contents/generations/tasks")) {
        actions.add("video:"+e.getRequestMethod()+" "+e.getRequestURI().getPath());
        headers.add(Map.copyOf(e.getRequestHeaders()));
        if(e.getRequestMethod().equals("POST")) bodies.add(json.readTree(e.getRequestBody()));
        byte[] bytes="{\"task_id\":\"platform-task\",\"upstream_task_id\":\"cgt-upstream\",\"status\":\"queued\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();return;
      }
      String action=e.getRequestURI().getQuery().split("&")[0].split("=")[1];
      actions.add(action); bodies.add(json.readTree(e.getRequestBody())); headers.add(Map.copyOf(e.getRequestHeaders()));
      String answer=action.equals(failedAction) ? (uncertain?"{}":"{\"ResponseMetadata\":{\"Error\":{\"Message\":\"SECRET unsafe upstream\"}}}")
          : switch(action) {
            case "CreateAssetGroup" -> "{\"Result\":{\"Id\":\"group-1\"}}";
            case "CreateAsset" -> "{\"Result\":{\"Id\":\"asset-1\"}}";
            default -> pollResponse!=null ? pollResponse : "{\"Result\":{\"Id\":\"asset-1\",\"GroupId\":\"group-1\",\"Status\":\""+assetStatus+"\"}}";
          };
      if(action.equals("GetAsset") && lateActiveArrived!=null) {
        if(racingPolls.incrementAndGet()==1) {
          answer="{\"Result\":{\"Id\":\"asset-1\",\"GroupId\":\"group-1\",\"Status\":\"Active\"}}";
          lateActiveArrived.countDown();
          try { releaseLateActive.await(5,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        } else answer="{\"Result\":{\"Id\":\"asset-1\",\"GroupId\":\"group-1\",\"Status\":\"Failed\"}}";
      }
      byte[] bytes=answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close();
    }); server.start();
    var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate(); jdbc=new JdbcTemplate(ds);
    env=new HashMap<>(Map.of("MEDIA_ASSET_PROVIDER_ID","ark","MEDIA_ASSET_ACCESS_KEY_ID","asset-ak",
        "MEDIA_ASSET_SECRET_ACCESS_KEY","asset-secret","MEDIA_ASSET_PROJECT_NAME","portrait-project",
        "MEDIA_ASSET_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort()));
    connections=mock(ArkConnectionStore.class);
    when(connections.load("ark")).thenReturn(new ArkConnectionStore.Connection(0,"ark","https://ark.cn-beijing.volces.com","generation-secret"));
    routing=mock(RoutingPort.class);
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,Map.of(),"ark")));
    storage=mock(StoragePort.class);
    when(storage.resolveReferences(anyString(),anyList(),eq(ReferenceMode.OMNI))).thenAnswer(inv -> {
      if(!inv.getArgument(0).equals("owner")) throw new DefinitiveRejectionException("REFERENCE_REJECTED");
      return List.of(storedKind==MediaReference.Kind.IMAGE ? new MediaReference(null,"https://cdn.example/portrait.png") : resolvedPortrait());
    });
    http=new ProviderHttp(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),json,Duration.ofSeconds(2));
    reviews=service();
  }
  JdbcPortraitReviews service() { return new JdbcPortraitReviews(jdbc,json,connections,routing,storage,http,clock,env); }
  void switchToMaas() {
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,Map.of(),"maas")));
    when(connections.load("maas")).thenReturn(new ArkConnectionStore.Connection(0,"maas",
        "http://127.0.0.1:"+server.getAddress().getPort()+"/doubao/api/v3","maas-key"));
    reviews=service();
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void switchingModelProviderPreservesOldReviewsAndFrozenTasksButIsolatesNewAssets(MediaReference.Kind kind) {
    storedKind=kind;var old=reviews.submit("owner","old-review","file-1","video",kind);
    assetStatus="Active";reviews.tick();reviews.tick();
    reviews.freeze("old-task","owner",request(old.id(),kind),route("old-task"));
    env.put("MEDIA_ASSET_PROJECT_NAMES","{\"maas\":\"proxy-project\"}");switchToMaas();
    var currentModels=routing.models();
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video","doubao-seedance-2-0-fast-260128",Map.of(),"maas")));
    assertEquals("ACTIVE",reviews.get("owner",old.id()).status());
    var oldSettings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    assertEquals(Map.of(0,"asset-1"),reviews.assets("old-task","owner",request(old.id(),kind).withReferences(List.of(resolvedPortrait())),route("old-task"),oldSettings).ids());
    when(routing.models()).thenReturn(currentModels);
    assertThrows(DefinitiveRejectionException.class,()->reviews.validate("owner",request(old.id(),kind)));
    var fresh=reviews.submit("owner","new-review","file-1","video",kind);assertNotEquals(old.id(),fresh.id());
    reviews.tick();reviews.tick();assertEquals("ACTIVE",reviews.get("owner",fresh.id()).status());
    var created=bodies.stream().filter(b->b.has("GroupId")&&b.path("GroupId").asText().equals("maas-group")).findFirst().orElseThrow();
    assertEquals(MODEL,created.path("model").asText());
    assertEquals(switch(kind){case IMAGE->"Image";case VIDEO->"Video";case AUDIO->"Audio";},created.path("AssetType").asText());
    assertEquals("proxy-project",created.path("ProjectName").asText());
    var maas=connections.load("maas");
    var target=new RouteSnapshot("new-task","video","maas","Maas","https://maas.example/doubao/api/v3",MODEL,"{}",route("unused").modelConfigurationJson(),clock.instant());
    reviews.freeze("new-task","owner",request(fresh.id(),kind),target);
    assertEquals(Map.of(0,"maas-asset"),reviews.assets("new-task","owner",request(fresh.id(),kind).withReferences(List.of(resolvedPortrait())),target,ProviderSettings.from(Map.of(),json).withConnection(maas)).ids());
    assertThrows(TaskNotFoundException.class,()->reviews.get("other",fresh.id()));
    assertTrue(actions.contains("maas:GET /v1/private-avatar/assets/maas-asset"));
    assertEquals("Bearer maas-key",headers.getLast().get("Authorization").getFirst());
    env.put("MEDIA_ASSET_PROJECT_NAMES","{\"maas\":\"rotated-project\"}");
    assertThrows(DefinitiveRejectionException.class,()->service().assets("new-task","owner",request(fresh.id(),kind).withReferences(List.of(resolvedPortrait())),target,ProviderSettings.from(Map.of(),json).withConnection(maas)));
    assertEquals("ACTIVE",service().get("owner",old.id()).status());
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void guideReviewAndProductionSubmissionCarryTheSameProjectForEveryKind(MediaReference.Kind kind) {
    env.put("MEDIA_ASSET_PROJECT_NAMES","{\"maas\":\"proxy-project\"}");
    env.remove("MEDIA_ASSET_ACCESS_KEY_ID");env.remove("MEDIA_ASSET_SECRET_ACCESS_KEY");switchToMaas();storedKind=kind;
    var receipt=reviews.submit("owner","guide","file-1","video",kind);reviews.tick();
    assertEquals("proxy-project",bodies.getFirst().path("ProjectName").asText());
    assertEquals("AIGC",bodies.getFirst().path("GroupType").asText());
    assertThrows(DefinitiveRejectionException.class,()->reviews.validate("owner",request(receipt.id(),kind)));
    maasPollResponse="{\"Id\":\"maas-asset\",\"GroupId\":\"maas-group\",\"Status\":\"Active\",\"ProjectName\":\"proxy-project\",\"AssetType\":\""+
        switch(kind){case IMAGE->"Image";case VIDEO->"Video";case AUDIO->"Audio";}+"\"}";
    reviews.tick();assertEquals("ACTIVE",reviews.get("owner",receipt.id()).status());
    assertEquals("ProjectName=proxy-project",queries.getLast());
    var connection=connections.load("maas");
    var target=new RouteSnapshot("guide-task","video","maas","Maas",connection.baseUrl(),MODEL,"{}",route("unused").modelConfigurationJson(),clock.instant());
    var original=new TaskRequest(MediaType.VIDEO,"video","hello","16:9","480p",1,5,request(receipt.id(),kind).references(),ReferenceMode.OMNI);reviews.freeze(target.taskId(),"owner",original,target);
    var resolved=original.withReferences(List.of(resolvedPortrait()));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connection);
    var provider=new ProductionProviderPort(ignored->settings,new ProviderJournal(jdbc,json),new ArkMediaAdapter(http),new TencentMpsAdapter(http,clock),reviews);
    provider.requireAvailable(resolved,target,"owner");provider.submit(target.taskId(),"owner",resolved,target);
    var payload=bodies.getLast();String type=kind.name().toLowerCase(Locale.ROOT);
    assertEquals("proxy-project",payload.path("ProjectName").asText());
    assertEquals("asset://maas-asset",payload.path("content").get(1).path(type+"_url").path("url").asText());
    assertEquals("reference_"+type,payload.path("content").get(1).path("role").asText());
    assertEquals(5,payload.path("duration").asInt());assertEquals("16:9",payload.path("ratio").asText());
    assertEquals("Bearer maas-key",headers.getLast().get("Authorization").getFirst());
    assertEquals("platform-task",jdbc.queryForObject("SELECT upstream_id FROM media_provider_operation",String.class));
    provider.submit(target.taskId(),"owner",resolved,target);
    assertEquals(1,actions.stream().filter(a->a.startsWith("video:POST")).count());
    maasPollResponse=maasPollResponse.replace("proxy-project","other-project");
    assertThrows(RuntimeException.class,()->reviews.validate("owner",original));
    maasPollResponse=maasPollResponse.replace("other-project","proxy-project").replace("\"AssetType\":\""+switch(kind){case IMAGE->"Image";case VIDEO->"Video";case AUDIO->"Audio";}+"\"","\"AssetType\":\"Wrong\"");
    assertThrows(RuntimeException.class,()->reviews.validate("owner",original));
  }
  @Test void defaultProjectPreservesExistingProxyIdentityButProjectRotationBlocksFrozenAssets() {
    env.remove("MEDIA_ASSET_PROJECT_NAME");switchToMaas();assetStatus="Active";
    var receipt=reviews.submit("owner","default","file-1","video");reviews.tick();reviews.tick();
    var connection=connections.load("maas");
    assertEquals(VolcanoAssetClient.identity(connection.baseUrl(),connection.apiKey(),"default"),
        jdbc.queryForObject("SELECT asset_identity FROM media_portrait WHERE id=?",String.class,receipt.id()));
    assertEquals("default",bodies.getFirst().path("ProjectName").asText());
    var target=new RouteSnapshot("default-task","video","maas","Maas",connection.baseUrl(),MODEL,"{}",route("unused").modelConfigurationJson(),clock.instant());
    reviews.freeze(target.taskId(),"owner",request(receipt.id()),target);
    env.put("MEDIA_ASSET_PROJECT_NAME","another-project");
    assertThrows(DefinitiveRejectionException.class,()->service().assets(target.taskId(),"owner",request(receipt.id()),target,ProviderSettings.from(Map.of(),json).withConnection(connection)));
  }
  @Test void invalidSupplierProjectBindingsNeverFallBackToTheGlobalProject() {
    for(String config:List.of("{", "[]", "null", "{} {\"ark\":\"other-project\"}",
        "{\"ark\":\"one\",\"ark\":\"two\"}", "{\"ark\":null}", "{\"ark\":123}",
        "{\"ark\":\"\"}", "{\"ark\":\"bad project\"}")) {
      env.put("MEDIA_ASSET_PROJECT_NAMES",config);
      assertThrows(RuntimeException.class,()->service().submit("owner","invalid-project","file-1","video"),config);
    }
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
    assertTrue(actions.isEmpty());
  }
  @Test void unknownOldChannelSubmissionDoesNotAliasNewChannelButSameChannelRotationStillDoes() {
    failedAction="CreateAsset";uncertain=true;
    var old=reviews.submit("owner","old-unknown","file-1","video");reviews.tick();
    assertEquals("UNCERTAIN",reviews.get("owner",old.id()).status());
    switchToMaas();var fresh=reviews.submit("owner","new-key","file-1","video");assertNotEquals(old.id(),fresh.id());
    jdbc.update("UPDATE media_portrait SET status='UNCERTAIN',stage='ASSET_SENDING' WHERE id=?",fresh.id());
    var connection=connections.load("maas");when(connections.load("maas")).thenReturn(new ArkConnectionStore.Connection(1,"maas",connection.baseUrl(),"rotated-key"));
    assertEquals(fresh.id(),service().submit("owner","another-key","file-1","video").id());
  }
  @AfterEach void close() { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
  TaskRequest request(String id) { return request(id,MediaReference.Kind.IMAGE); }
  TaskRequest request(String id,MediaReference.Kind kind) { return new TaskRequest(MediaType.VIDEO,"video","hello","16:9","720p",1,5,
      List.of(new MediaReference("file-1",null,kind,kind==MediaReference.Kind.IMAGE?null:3.25,id)),ReferenceMode.OMNI); }
  RouteSnapshot route(String task) { return new RouteSnapshot(task,"video","ark","Ark","https://ark.cn-beijing.volces.com",MODEL,
      "{}","{\"type\":\"VIDEO\",\"resolutions\":[\"480p\",\"720p\"],\"aspectRatios\":[\"16:9\"],\"minDurationSeconds\":4,\"maxDurationSeconds\":30}",clock.instant()); }
  @Test void missingModelProviderNeverFallsBackToTheLegacyReviewProvider() {
    for(String provider:Arrays.asList(null,""," ")) {
      when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,Map.of(),provider)));
      assertThrows(DependencyUnavailableException.class,()->reviews.submit("owner","missing-provider","file-1","video"));
    }
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
    verifyNoInteractions(connections,storage);
  }
  @Test void reviewBindsProviderAndModelFromOneCatalogSnapshot() {
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,Map.of(),"ark")),
        List.of(new RoutingPort.Model("video","doubao-seedance-2-0-260128",Map.of(),"maas")));
    var receipt=reviews.submit("owner","snapshot","file-1","video");
    assertEquals("ark",jdbc.queryForObject("SELECT provider_id FROM media_portrait WHERE id=?",String.class,receipt.id()));
    assertEquals(MODEL,jdbc.queryForObject("SELECT provider_model FROM media_portrait WHERE id=?",String.class,receipt.id()));
    verify(routing,times(1)).models();
  }
  @Test void signedReviewContinuesWithoutBrowserAndOnlyActiveCanBeFrozen() {
    var receipt=reviews.submit("owner","key-1","file-1","video");
    assertEquals("SUBMITTING",receipt.status());
    assertTrue(actions.isEmpty());
    assertEquals("GROUP_READY",jdbc.queryForObject("SELECT stage FROM media_portrait WHERE id=?",String.class,receipt.id()));
    service().tick();
    assertEquals("PROCESSING",reviews.get("owner",receipt.id()).status());
    assertEquals(List.of("CreateAssetGroup","CreateAsset","GetAsset"),actions);
    assertEquals("AIGC",bodies.getFirst().path("GroupType").asText());
    assertEquals("portrait-project",bodies.getFirst().path("ProjectName").asText());
    assertEquals("Image",bodies.get(1).path("AssetType").asText());
    assertEquals("https://cdn.example/portrait.png",bodies.get(1).path("URL").asText());
    String signature=headers.getFirst().get("Authorization").getFirst();
    assertTrue(signature.startsWith("HMAC-SHA256 Credential=asset-ak/20260910/cn-beijing/ark/request, "));
    assertTrue(signature.matches(".*Signature=[a-f0-9]{64}"));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("owner",request(receipt.id())));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_task",Integer.class));
    assetStatus="Active"; service().tick();
    assertEquals("ACTIVE",reviews.get("owner",receipt.id()).status());
    reviews.validate("owner",request(receipt.id()));
    reviews.freeze("task-1","owner",request(receipt.id()),route("task-1"));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    assertEquals(Map.of(0,"asset-1"),reviews.assets("task-1","owner",request(receipt.id()).withReferences(List.of(new MediaReference(null,"https://cdn.example/portrait.png"))),route("task-1"),settings).ids());
    verify(routing,never()).route(anyString(),anyString());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void compatibleModelsReuseReviewedMediaThroughQuoteFreezeAndFinalSubmission(MediaReference.Kind kind) throws Exception {
    storedKind=kind;
    var receipt=reviews.submit("owner","one","file-1","video",kind);
    assetStatus="Active"; reviews.tick(); reviews.tick();
    String targetModel="doubao-seedance-2-0-260128";
    var capability=Map.<String,Object>of("type","VIDEO","displayName","Target","resolutions",List.of("480p"),
        "aspectRatios",List.of("16:9"),"maxCount",1,"minDurationSeconds",4,"maxDurationSeconds",15);
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,capability,"ark"),
        new RoutingPort.Model("target",targetModel,capability,"ark")));
    var original=new TaskRequest(MediaType.VIDEO,"target","hello","16:9","480p",1,4,
        List.of(new MediaReference("ordinary-file",null),request(receipt.id(),kind).references().getFirst()),ReferenceMode.OMNI);
    var refs=List.of(new MediaReference(null,"https://cdn.example/ordinary.png"),resolvedPortrait());
    when(storage.resolveReferences(eq("owner"),eq(original.references()),eq(ReferenceMode.OMNI))).thenReturn(refs);
    var billing=mock(BillingPort.class);
    when(billing.availablePrices("owner")).thenReturn(List.of(new BillingPort.AvailablePrice("target","480p",java.math.BigDecimal.ONE)));
    when(billing.quote("owner",original)).thenReturn(new java.math.BigDecimal("12.3456"));
    var catalog=new com.xzwl.cloud.media.application.catalog.GenerationCatalogService(routing,billing,storage,reviews);
    assertEquals(new java.math.BigDecimal("12.3456"),catalog.quote("owner",original));
    var target=new RouteSnapshot("cross-model","target","ark","Ark","https://ark.cn-beijing.volces.com",targetModel,
        "{}",json.writeValueAsString(capability),clock.instant());
    reviews.freeze("cross-model","owner",original,target);
    var resolved=original.withReferences(refs);
    var generationHttp=mock(ProviderHttp.class);
    when(generationHttp.encode(any())).thenAnswer(inv -> json.writeValueAsString(inv.getArgument(0)));
    when(generationHttp.call(anyString(),anyString(),anyMap(),anyString(),anyInt(),anyInt())).thenReturn(json.readTree("{\"id\":\"video-task-1\"}"));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    var provider=new ProductionProviderPort(ignored -> settings,new ProviderJournal(jdbc,json),new ArkMediaAdapter(generationHttp),new TencentMpsAdapter(generationHttp,clock),reviews);
    provider.requireAvailable(resolved,target,"owner");
    provider.submit("cross-model","owner",resolved,target);
    var body=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(generationHttp).call(eq("POST"),eq("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks"),anyMap(),body.capture(),eq(180),eq(0));
    var payload=json.readTree(body.getValue());
    assertEquals(targetModel,payload.path("model").asText());
    assertEquals("asset://asset-1",payload.path("content").get(2).path(kind.name().toLowerCase(Locale.ROOT)+"_url").path("url").asText());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
    assertEquals("video",reviews.get("owner",receipt.id()).model());
    verify(billing,never()).charge(anyString(),anyString(),any(),any());
    var wrongTarget=new RouteSnapshot("cross-model","target","ark","Ark","https://ark.cn-beijing.volces.com",MODEL,"{}","{}",clock.instant());
    assertThrows(DefinitiveRejectionException.class,() -> reviews.assets("cross-model","owner",resolved,wrongTarget,settings));
    env.put("MEDIA_ASSET_PROJECT_NAME","different-project");
    assertThrows(DefinitiveRejectionException.class,() -> service().validate("owner",original));
    assertThrows(DefinitiveRejectionException.class,() -> service().assets("cross-model","owner",resolved,target,settings));
  }
  @Test void anotherFileCanBeSubmittedWhileFirstReviewWaitsForTheWorker() {
    var first=reviews.submit("owner","first","file-1","video");
    var second=reviews.submit("owner","second","file-2","video");
    assertEquals("SUBMITTING",first.status()); assertEquals("SUBMITTING",second.status());
    assertNotEquals(first.id(),second.id()); assertTrue(actions.isEmpty());
    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
    service().tick();
    assertEquals(2,Collections.frequency(actions,"CreateAsset"));
  }
  @Test void definitiveCreateFailuresCanRetryWithANewKeyWhileOldKeysKeepTheirReceipt() {
    for(String action:List.of("CreateAssetGroup","CreateAsset")) {
      failedAction=action;
      String file="file-"+action;
      var failed=reviews.submit("owner","old-"+action,file,"video"); reviews.tick();
      assertEquals("FAILED",reviews.get("owner",failed.id()).status());
      int previousPosts=actions.size();
      var retried=reviews.submit("owner","new-"+action,file,"video");
      assertNotEquals(failed.id(),retried.id()); assertEquals("SUBMITTING",retried.status());
      assertEquals(previousPosts,actions.size());
      assertEquals(failed.id(),reviews.submit("owner","old-"+action,file,"video").id());
      assertEquals("FAILED",reviews.submit("owner","old-"+action,file,"video").status());
      assertEquals(retried.id(),reviews.submit("owner","new-"+action,file,"video").id());
      assertEquals(retried.id(),reviews.submit("owner","another-"+action,file,"video").id());
      failedAction=null; reviews.tick();
      assertEquals("PROCESSING",reviews.get("owner",retried.id()).status());
    }
  }
  @Test void concurrentFailedRetriesCreateOneNewAttemptAndPreserveFrozenTaskMapping() throws Exception {
    var failed=reviews.submit("owner","old","file-1","video"); reviews.tick();
    assetStatus="Active"; reviews.tick();
    assertEquals(failed.id(),reviews.submit("owner","active-alias","file-1","video").id());
    reviews.freeze("frozen-task","owner",request(failed.id()),route("frozen-task"));
    String mapping=jdbc.queryForObject("SELECT mapping_json FROM media_portrait_task WHERE task_id='frozen-task'",String.class);
    assetStatus="Failed"; assertEquals("FAILED",reviews.get("owner",failed.id()).status());
    int previousPosts=actions.size();
    try(var pool=Executors.newFixedThreadPool(4)) {
      var jobs=new ArrayList<Callable<String>>(); var start=new CountDownLatch(1);
      for(int i=0;i<8;i++) { final int key=i; jobs.add(() -> { start.await(); return service().submit("owner","retry-"+key,"file-1","video").id(); }); }
      var futures=new ArrayList<Future<String>>(); for(var job:jobs) futures.add(pool.submit(job)); start.countDown();
      var ids=new HashSet<String>(); for(var result:futures) ids.add(result.get(5,TimeUnit.SECONDS));
      assertEquals(1,ids.size()); assertFalse(ids.contains(failed.id()));
      assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
      assertEquals(previousPosts,actions.size());
      assertEquals(failed.id(),reviews.submit("owner","old","file-1","video").id());
      assertEquals(mapping,jdbc.queryForObject("SELECT mapping_json FROM media_portrait_task WHERE task_id='frozen-task'",String.class));
      reviews.tick(); assertEquals(2,Collections.frequency(actions,"CreateAssetGroup")); assertEquals(2,Collections.frequency(actions,"CreateAsset"));
      assetStatus="Active"; reviews.tick();
      var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
      assertThrows(DefinitiveRejectionException.class,() -> reviews.assets("frozen-task","owner",request(failed.id()),route("frozen-task"),settings));
    }
  }
  @Test void uncertainAndInFlightReviewsCannotBeRecreatedByNewKeysOrChangedConfiguration() {
    var receipt=reviews.submit("owner","old","file-1","video");
    for(String stage:List.of("GROUP_SENDING","ASSET_SENDING")) {
      jdbc.update("UPDATE media_portrait SET stage=? WHERE id=?",stage,receipt.id());
      assertEquals(receipt.id(),reviews.submit("owner","key-"+stage,"file-1","video").id());
    }
    when(connections.load("ark")).thenThrow(new DependencyUnavailableException("rotating"));
    assertEquals(receipt.id(),service().submit("owner","rotated-in-flight","file-1","video").id());
    jdbc.update("UPDATE media_portrait SET status='UNCERTAIN' WHERE id=?",receipt.id());
    assertEquals(receipt.id(),service().submit("owner","rotated-unknown","file-1","video").id());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class)); assertTrue(actions.isEmpty());
  }
  @Test void replayIsBeforeConfigAndNewKeysReuseSameReviewWithoutCrossOwnerDisclosure() {
    var receipt=reviews.submit("owner","one","file-1","video");
    assertEquals(receipt.id(),reviews.submit("owner","two","file-1","video").id());
    reviews.tick();
    assertEquals(receipt.id(),reviews.submit("owner","processing-alias","file-1","video").id());
    when(connections.load("ark")).thenThrow(new DependencyUnavailableException("rotating"));
    assertEquals(receipt.id(),reviews.submit("owner","one","file-1","video").id());
    assertEquals(receipt.id(),reviews.submit("owner","two","file-1","video").id());
    assertThrows(TaskConflictException.class,() -> reviews.submit("owner","one","other-file","video"));
    assertThrows(TaskNotFoundException.class,() -> reviews.get("other",receipt.id()));
    assertTrue(reviews.list("other",20,null).isEmpty());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
  }
  @Test void uncertainCreationAndCrashNeverRepeatPostOnRestartOrChangedKey() {
    failedAction="CreateAsset"; uncertain=true;
    var receipt=reviews.submit("owner","one","file-1","video");
    reviews.tick();
    assertEquals("UNCERTAIN",reviews.get("owner",receipt.id()).status());
    assertFalse(reviews.get("owner",receipt.id()).errorMessage().contains("SECRET"));
    reviews=service(); reviews.tick();
    assertEquals(receipt.id(),reviews.submit("owner","two","file-1","video").id());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
    jdbc.update("UPDATE media_portrait SET status='SUBMITTING',stage='ASSET_SENDING',updated_at=0 WHERE id=?",receipt.id());
    reviews.tick(); assertEquals("UNCERTAIN",reviews.get("owner",receipt.id()).status());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
  }
  @Test void failedPollAndTransientPollAreSafeAndDoNotCreateAgain() {
    var receipt=reviews.submit("owner","one","file-1","video");
    reviews.tick();
    failedAction="GetAsset"; uncertain=true; reviews.tick();
    assertEquals("PROCESSING",reviews.get("owner",receipt.id()).status());
    failedAction=null; assetStatus="Failed"; reviews.tick();
    assertEquals("FAILED",reviews.get("owner",receipt.id()).status());
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
  }
  @Test void configRotationInvalidatesActiveReviewIncludingVersionZeroKeyRotation() {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    reviews.freeze("task-1","owner",request(receipt.id()),route("task-1"));
    when(connections.load("ark")).thenReturn(new ArkConnectionStore.Connection(0,"ark","https://ark.cn-beijing.volces.com","rotated"));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("owner",request(receipt.id())));
    assertEquals("FAILED",reviews.get("owner",receipt.id()).status());
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.assets("task-1","owner",request(receipt.id()),route("task-1"),settings));
  }
  @Test void concurrentKeysSubmitOnlyOneGroupAndAsset() throws Exception {
    try(var pool=Executors.newFixedThreadPool(4)) {
      var jobs=new ArrayList<Callable<String>>();
      for(int i=0;i<8;i++) { final int key=i; jobs.add(() -> reviews.submit("owner","key-"+key,"file-1","video").id()); }
      var ids=new HashSet<String>(); for(var result:pool.invokeAll(jobs)) ids.add(result.get());
      assertEquals(1,ids.size()); assertTrue(actions.isEmpty()); reviews.tick();
      assertEquals(1,Collections.frequency(actions,"CreateAssetGroup"));
      assertEquals(1,Collections.frequency(actions,"CreateAsset"));
    }
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void finalArkPayloadUsesFrozenAssetsAtOnlyTheVerifiedPositionAndRotationsCannotResend(MediaReference.Kind kind) throws Exception {
    storedKind=kind;
    var receipt=reviews.submit("owner","one","file-1","video",kind); assetStatus="Active"; reviews.tick(); reviews.tick();
    var original=new TaskRequest(MediaType.VIDEO,"video","hello","16:9","480p",1,5,List.of(
        new MediaReference("ordinary-file",null),request(receipt.id(),kind).references().getFirst(),
        new MediaReference("audio-file",null,MediaReference.Kind.AUDIO,3.0)),ReferenceMode.OMNI);
    var route=route("task-1"); reviews.freeze("task-1","owner",original,route);
    var resolved=original.withReferences(List.of(new MediaReference(null,"https://cdn.example/ordinary.png"),
        resolvedPortrait(),new MediaReference(null,"https://cdn.example/audio.mp3",MediaReference.Kind.AUDIO,3.0)));
    var generationHttp=mock(ProviderHttp.class);
    when(generationHttp.encode(any())).thenAnswer(inv -> json.writeValueAsString(inv.getArgument(0)));
    when(generationHttp.call(anyString(),anyString(),anyMap(),anyString(),anyInt(),anyInt())).thenReturn(json.readTree("{\"id\":\"video-task-1\"}"));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    var journal=new ProviderJournal(jdbc,json);
    var provider=new ProductionProviderPort(ignored -> settings,journal,new ArkMediaAdapter(generationHttp),new TencentMpsAdapter(generationHttp,clock),reviews);
    provider.requireAvailable(resolved,route,"owner");
    assertEquals("task-1",provider.submit("task-1","owner",resolved,route));
    var body=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(generationHttp).call(eq("POST"),eq("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks"),anyMap(),body.capture(),eq(180),eq(0));
    var content=json.readTree(body.getValue()).path("content");
    assertEquals("https://cdn.example/ordinary.png",content.get(1).path("image_url").path("url").asText());
    assertEquals("asset://asset-1",content.get(2).path(kind.name().toLowerCase(Locale.ROOT)+"_url").path("url").asText());
    assertEquals("reference_"+kind.name().toLowerCase(Locale.ROOT),content.get(2).path("role").asText());
    assertEquals("https://cdn.example/audio.mp3",content.get(3).path("audio_url").path("url").asText());
    assertEquals("reference_audio",content.get(3).path("role").asText());
    provider.submit("task-1","owner",resolved,route);
    verify(generationHttp,times(1)).call(anyString(),anyString(),anyMap(),anyString(),eq(180),eq(0));
    reviews.freeze("task-2","owner",original,route("task-2"));
    var rotated=ProviderSettings.from(Map.of(),json).withConnection(new ArkConnectionStore.Connection(0,"ark","https://ark.cn-beijing.volces.com","rotation"));
    var afterRotation=new ProductionProviderPort(ignored -> rotated,journal,new ArkMediaAdapter(generationHttp),new TencentMpsAdapter(generationHttp,clock),reviews);
    assertThrows(DefinitiveRejectionException.class,() -> afterRotation.submit("task-2","owner",resolved,route("task-2")));
    verify(generationHttp,times(1)).call(anyString(),anyString(),anyMap(),anyString(),eq(180),eq(0));
  }
  @Test void fileModelProviderAndAssetCredentialMismatchesAreRejectedBeforeAnyTaskBinding() {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    var wrongFile=new TaskRequest(MediaType.VIDEO,"video","hello","16:9","720p",1,5,
        List.of(new MediaReference("wrong-file",null,MediaReference.Kind.IMAGE,null,receipt.id())),ReferenceMode.OMNI);
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("owner",wrongFile));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("other",request(receipt.id())));
    var wrongProvider=new RouteSnapshot("task-1","video","other","Other","https://ark.cn-beijing.volces.com",MODEL,"{}","{}",clock.instant());
    assertThrows(DefinitiveRejectionException.class,() -> reviews.freeze("task-1","owner",request(receipt.id()),wrongProvider));
    env.put("MEDIA_ASSET_SECRET_ACCESS_KEY","rotated");
    assertThrows(DefinitiveRejectionException.class,() -> service().validate("owner",request(receipt.id())));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_task",Integer.class));
  }
  @Test void missingOrWrongAssetAndGroupCannotProveActive() {
    for(String invalid:List.of("{\"GroupId\":\"group-1\",\"Status\":\"Active\"}",
        "{\"Id\":\"other\",\"GroupId\":\"group-1\",\"Status\":\"Active\"}",
        "{\"Id\":\"asset-1\",\"Status\":\"Active\"}",
        "{\"Id\":\"asset-1\",\"GroupId\":\"other\",\"Status\":\"Active\"}")) {
      pollResponse="{\"Result\":"+invalid+"}";
      var receipt=reviews.submit("owner",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"video");
      reviews.tick(); reviews.tick(); assertEquals("PROCESSING",reviews.get("owner",receipt.id()).status());
    }
  }
  @Test void revokedDeletedOrUnknownPreviouslyActiveAssetCannotBeQuotedFrozenOrSent() {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    reviews.freeze("task-before-revocation","owner",request(receipt.id()),route("task-before-revocation"));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    for(String invalid:List.of("{\"ResponseMetadata\":{\"Error\":{\"Code\":\"NotFound\"}}}",
        "{\"Result\":{\"Id\":\"asset-1\",\"GroupId\":\"group-1\",\"Status\":\"Unknown\"}}",
        "{\"Result\":{\"Id\":\"asset-1\",\"GroupId\":\"group-1\",\"Status\":\"Failed\"}}")) {
      jdbc.update("UPDATE media_portrait SET status='ACTIVE' WHERE id=?",receipt.id());
      pollResponse=invalid;
      assertNotEquals("ACTIVE",reviews.get("owner",receipt.id()).status());
      assertThrows(RuntimeException.class,() -> reviews.validate("owner",request(receipt.id())));
      assertThrows(RuntimeException.class,() -> reviews.freeze("task-after-revocation","owner",request(receipt.id()),route("task-after-revocation")));
      assertThrows(RuntimeException.class,() -> reviews.assets("task-before-revocation","owner",request(receipt.id()),route("task-before-revocation"),settings));
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_task",Integer.class));
    }
  }
  @Test void unknownOrRevokedActiveReviewBlocksTheWorkerBeforeCharging() {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    var request=request(receipt.id());
    for(String status:List.of("Unknown","Failed")) {
      jdbc.update("UPDATE media_portrait SET status='ACTIVE' WHERE id=?",receipt.id()); assetStatus=status;
      String task="task-"+status;
      var mediaStore=mock(MediaTaskStore.class); var billing=mock(BillingPort.class); var provider=mock(ProviderPort.class);
      var data=new TaskData(request,null,null,null,null,null,null);
      var mediaTask=new MediaTask(task,"owner",task,TaskStatus.ROUTING,data,clock.instant(),clock.instant().plusSeconds(60),clock.instant(),0);
      when(mediaStore.claim(eq(task),anyString(),any(),any())).thenAnswer(inv -> Optional.of(new MediaTaskStore.Lease(mediaTask,inv.getArgument(1))));
      when(routing.route(task,"video")).thenReturn(route(task));
      var policy=new TaskPolicy(Duration.ofMinutes(1),Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30),10);
      new MediaTaskWorker(mediaStore,routing,billing,provider,storage,clock,policy,reviews).process(task);
      verify(billing,never()).charge(anyString(),anyString(),any(),any());
      verify(provider,never()).submit(anyString(),anyString(),any(),any());
      var saved=org.mockito.ArgumentCaptor.forClass(MediaTask.class);
      verify(mediaStore).save(any(),saved.capture(),anyList(),any());
      assertNotEquals(TaskStatus.CHARGE_PENDING,saved.getValue().status());
    }
  }
  @Test void delayedActiveReceiptCannotOverrideConcurrentFailureOrAuthorizeTask() throws Exception {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    lateActiveArrived=new CountDownLatch(1); releaseLateActive=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var delayed=pool.submit(() -> reviews.freeze("late-task","owner",request(receipt.id()),route("late-task")));
      try {
        assertTrue(lateActiveArrived.await(2,TimeUnit.SECONDS));
        assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("owner",request(receipt.id())));
      } finally { releaseLateActive.countDown(); }
      var rejected=assertThrows(ExecutionException.class,() -> delayed.get(3,TimeUnit.SECONDS));
      assertInstanceOf(DependencyUnavailableException.class,rejected.getCause());
      assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM media_portrait WHERE id=?",String.class,receipt.id()));
      assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_task",Integer.class));
    }
  }
  @Test void replayTransactionUsesReadCommittedWithoutChangingTheDatasourceDefault() throws Exception {
    String url=((DriverManagerDataSource)jdbc.getDataSource()).getUrl();
    var datasource=new DriverManagerDataSource(url,"sa","") {
      @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
        var connection=super.getConnection(); connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ); return connection;
      }
    };
    var checked=new java.util.concurrent.atomic.AtomicInteger();
    jdbc=new JdbcTemplate(datasource) {
      @Override public int update(String sql,Object... args) {
        if(sql.startsWith("INSERT INTO media_portrait")) {
          assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,
              execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) java.sql.Connection::getTransactionIsolation));
          checked.incrementAndGet();
        }
        return super.update(sql,args);
      }
    };
    reviews=service(); reviews.submit("owner","one","file-1","video");
    assertTrue(checked.get()>=2);
    try(var connection=datasource.getConnection()) { assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation()); }
  }
  @Test void quoteChecksReviewWithoutRoutingChargingOrFreezingAnything() {
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video",MODEL,Map.of("type","VIDEO","displayName","Video",
        "resolutions",List.of("720p"),"aspectRatios",List.of("16:9"),"maxCount",1,"minDurationSeconds",4,"maxDurationSeconds",30),"ark")));
    var billing=mock(BillingPort.class);
    when(billing.availablePrices("owner")).thenReturn(List.of(new BillingPort.AvailablePrice("video","720p",java.math.BigDecimal.ONE)));
    when(billing.quote(eq("owner"),any())).thenReturn(new java.math.BigDecimal("12.3456"));
    var catalog=new com.xzwl.cloud.media.application.catalog.GenerationCatalogService(routing,billing,storage,reviews);
    var receipt=reviews.submit("owner","one","file-1","video");
    assertThrows(DefinitiveRejectionException.class,() -> catalog.quote("owner",request(receipt.id())));
    verify(billing,never()).quote(anyString(),any());
    assetStatus="Active"; reviews.tick(); reviews.tick();
    assertEquals(new java.math.BigDecimal("12.3456"),catalog.quote("owner",request(receipt.id())));
    verify(billing,never()).charge(anyString(),anyString(),any(),any()); verify(routing,never()).route(anyString(),anyString());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_task",Integer.class));
    assertEquals(2,actions.stream().filter(a -> a.startsWith("Create")).count());
  }
  @Test void signatureMatchesIndependentFixedVector() throws Exception {
    var signedHttp=mock(ProviderHttp.class);
    String payload="{\"Id\":\"asset-1\",\"ProjectName\":\"portrait-project\"}";
    when(signedHttp.encode(any())).thenReturn(payload);
    when(signedHttp.call(anyString(),anyString(),anyMap(),anyString())).thenReturn(json.readTree("{\"Result\":{\"Status\":\"Active\"}}"));
    new VolcanoAssetClient(signedHttp,clock).call("https://ark.cn-beijing.volcengineapi.com","asset-ak","asset-secret","GetAsset",Map.of("Id","asset-1","ProjectName","portrait-project"));
    verify(signedHttp).call("POST","https://ark.cn-beijing.volcengineapi.com/?Action=GetAsset&Version=2024-01-01",Map.of(
        "Content-Type","application/json","X-Date","20260910T123000Z","X-Content-Sha256","e20f69d2dd8c533721314ac1d2e44e87997cc11ac16888990ca5aabad631a980",
        "Authorization","HMAC-SHA256 Credential=asset-ak/20260910/cn-beijing/ark/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=697010ecae9572d95ea6f3609f8827fca481718d1eef31d6c792900ccbdb48ff"),payload);
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void databaseReopenContinuesKnownAssetWithoutRepeatingCreation(MediaReference.Kind kind,@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
    storedKind=kind;
    String url="jdbc:h2:file:"+directory.resolve("portraits")+";MODE=MySQL";
    var datasource=new DriverManagerDataSource(url,"sa","");
    Flyway.configure().dataSource(datasource).locations("classpath:db/media-migration/common").load().migrate();
    jdbc=new JdbcTemplate(datasource); reviews=service();
    var receipt=reviews.submit("owner","one","file-1","video",kind); reviews.tick(); jdbc.execute("SHUTDOWN");
    jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); reviews=service();
    assetStatus="Active"; reviews.tick();
    var reopened=reviews.get("owner",receipt.id());
    assertEquals("ACTIVE",reopened.status());
    assertEquals(kind,reopened.kind()); assertEquals(storedDuration(),reopened.durationSeconds());
    assertEquals(kind,reviews.list("owner",20,null).getFirst().kind());
    assertEquals(kind.name(),jdbc.queryForObject("SELECT kind FROM media_portrait WHERE id=?",String.class,receipt.id()));
    assertEquals(storedDuration(),jdbc.queryForObject("SELECT duration_seconds FROM media_portrait WHERE id=?",Double.class,receipt.id()));
    assertEquals(switch(kind) { case IMAGE -> "Image"; case VIDEO -> "Video"; case AUDIO -> "Audio"; },bodies.get(1).path("AssetType").asText());
    assertTrue(headers.get(1).get("Authorization").getFirst().matches("HMAC-SHA256 Credential=asset-ak/.*Signature=[a-f0-9]{64}"));
    verify(storage,atLeastOnce()).resolveReferences("owner",List.of(new MediaReference("file-1",null,kind,null)),ReferenceMode.OMNI);
    assertEquals(1,Collections.frequency(actions,"CreateAssetGroup")); assertEquals(1,Collections.frequency(actions,"CreateAsset"));
    assertEquals(receipt.id(),reviews.submit("owner","one","file-1","video",kind).id());
    jdbc.execute("SHUTDOWN");
  }
  @Test void concurrentConflictingSameKeyLeavesNoOrphanForBackgroundCreation() throws Exception {
    try(var pool=Executors.newFixedThreadPool(2)) {
      var results=pool.invokeAll(List.<Callable<String>>of(() -> reviews.submit("owner","one","file-1","video").id(),
          () -> reviews.submit("owner","one","file-2","video").id()));
      int conflicts=0;
      for(var result:results) try { assertNotNull(result.get()); } catch(ExecutionException conflict) {
        assertInstanceOf(TaskConflictException.class,conflict.getCause()); conflicts++;
      }
      assertEquals(1,conflicts);
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
      reviews.tick(); assertEquals(1,Collections.frequency(actions,"CreateAssetGroup")); assertEquals(1,Collections.frequency(actions,"CreateAsset"));
    }
  }
  @Test void ownerScopedPaginationIsExclusiveAndUnknownOtherOwnerCursorIsNotDisclosed() {
    reviews.submit("owner","one","file-1","video"); reviews.submit("owner","two","file-2","video");
    var all=reviews.list("owner",20,null); assertEquals(2,all.size());
    assertEquals(List.of(all.get(1).id()),reviews.list("owner",20,all.getFirst().id()).stream().map(PortraitReviews.Portrait::id).toList());
    assertThrows(TaskNotFoundException.class,() -> reviews.list("other",20,all.getFirst().id()));
    assertThrows(IllegalArgumentException.class,() -> reviews.list("owner",101,null));
  }
  @Test void publicReferencesAllowUploadedPortraitKindsButNeverAssetUrlsOrFirstLast() {
    assertThrows(IllegalArgumentException.class,() -> new MediaReference(null,"asset://asset-1"));
    assertThrows(IllegalArgumentException.class,() -> new MediaReference(null,"https://cdn.example/image",MediaReference.Kind.IMAGE,null,"review"));
    assertDoesNotThrow(() -> new MediaReference("file",null,MediaReference.Kind.VIDEO,null,"review"));
    assertDoesNotThrow(() -> new MediaReference("file",null,MediaReference.Kind.AUDIO,3.0,"review"));
    assertThrows(IllegalArgumentException.class,() -> new TaskRequest(MediaType.VIDEO,"video","hi","720p",1,5,request("review").references()));
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void kindIsBoundToReplayFrozenMappingAndResolvedStorage(MediaReference.Kind kind) {
    storedKind=kind;
    var receipt=reviews.submit("owner","one","file-1","video",kind);
    assertEquals(receipt.id(),reviews.submit("owner","alias","file-1","video",kind).id());
    var wrong=kind==MediaReference.Kind.IMAGE ? MediaReference.Kind.VIDEO : MediaReference.Kind.IMAGE;
    assertThrows(TaskConflictException.class,() -> reviews.submit("owner","one","file-1","video",wrong));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.submit("owner","wrong","file-1","video",wrong));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.submit("other","one","file-1","video",kind));
    assetStatus="Active"; reviews.tick(); reviews.tick();
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("owner",request(receipt.id(),wrong)));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.validate("other",request(receipt.id(),kind)));
    reviews.freeze("task-1","owner",request(receipt.id(),kind),route("task-1"));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.assets("task-1","owner",
        request(receipt.id(),kind).withReferences(List.of(new MediaReference(null,"https://cdn.example/wrong",wrong,3.25))),route("task-1"),settings));
    jdbc.update("UPDATE media_portrait SET kind=? WHERE id=?",wrong.name(),receipt.id());
    assertThrows(DefinitiveRejectionException.class,() -> reviews.assets("task-1","owner",
        request(receipt.id(),kind).withReferences(List.of(resolvedPortrait())),route("task-1"),settings));
    assertEquals(1,Collections.frequency(actions,"CreateAsset"));
  }
  @ParameterizedTest @EnumSource(value=MediaReference.Kind.class,names={"VIDEO","AUDIO"})
  void missingDurationAndChangedStorageMetadataNeverReachCreate(MediaReference.Kind kind) {
    when(storage.resolveReferences(anyString(),anyList(),eq(ReferenceMode.OMNI)))
        .thenReturn(List.of(new MediaReference(null,"https://cdn.example/portrait",kind,null)));
    assertThrows(DefinitiveRejectionException.class,() -> reviews.submit("owner","missing","file-1","video",kind));
    when(storage.resolveReferences(anyString(),anyList(),eq(ReferenceMode.OMNI)))
        .thenReturn(List.of(new MediaReference(null,"https://cdn.example/portrait",kind,3.25)));
    var receipt=reviews.submit("owner","one","file-1","video",kind);
    when(storage.resolveReferences(anyString(),anyList(),eq(ReferenceMode.OMNI)))
        .thenReturn(List.of(new MediaReference(null,"https://cdn.example/portrait",kind,4.0)));
    reviews.tick();
    assertEquals("FAILED",reviews.get("owner",receipt.id()).status()); assertTrue(actions.isEmpty());
  }
  @ParameterizedTest @EnumSource(MediaReference.Kind.class)
  void unknownReviewRejectsKindChangesWithoutConsumingAnotherKey(MediaReference.Kind kind) {
    storedKind=kind;
    var receipt=reviews.submit("owner","one","file-1","video",kind);
    jdbc.update("UPDATE media_portrait SET status='UNCERTAIN' WHERE id=?",receipt.id());
    var wrong=kind==MediaReference.Kind.IMAGE?MediaReference.Kind.VIDEO:MediaReference.Kind.IMAGE;
    assertThrows(TaskConflictException.class,() -> reviews.submit("owner","wrong","file-1","video",wrong));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait_request",Integer.class));
    when(connections.load("ark")).thenThrow(new DependencyUnavailableException("rotating"));
    assertEquals(receipt.id(),service().submit("owner","alias","file-1","video",kind).id());
    assertTrue(actions.isEmpty());
  }
  @Test void versionTwelveBackfillsExistingReviewsAsImages() {
    var datasource=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(datasource).locations("classpath:db/media-migration/common").target("11").load().migrate();
    jdbc=new JdbcTemplate(datasource);
    jdbc.update("INSERT INTO media_portrait (id,owner_hash,owner_id,file_id,model,reuse_hash,provider_id,provider_model,generation_identity,asset_identity,project_name,status,stage,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        "historical",VolcanoAssetClient.identity("owner"),"owner","file-1","video","reuse","ark",MODEL,"generation","asset","portrait-project","FAILED","POLL",0,0);
    Flyway.configure().dataSource(datasource).locations("classpath:db/media-migration/common").load().migrate();
    var historical=service().get("owner","historical");
    assertEquals(MediaReference.Kind.IMAGE,historical.kind()); assertNull(historical.durationSeconds());
  }
  @Test void historicalFrozenMappingDefaultsToImage() throws Exception {
    var receipt=reviews.submit("owner","one","file-1","video"); assetStatus="Active"; reviews.tick(); reviews.tick();
    reviews.freeze("task-1","owner",request(receipt.id()),route("task-1"));
    var mapping=json.readTree(jdbc.queryForObject("SELECT mapping_json FROM media_portrait_task",String.class));
    ((com.fasterxml.jackson.databind.node.ObjectNode)mapping.get(0)).remove("kind");
    jdbc.update("UPDATE media_portrait_task SET mapping_json=?",json.writeValueAsString(mapping));
    var settings=ProviderSettings.from(Map.of(),json).withConnection(connections.load("ark"));
    assertEquals(Map.of(0,"asset-1"),service().assets("task-1","owner",request(receipt.id()).withReferences(List.of(resolvedPortrait())),route("task-1"),settings).ids());
  }
  @Test void assetConfigurationIsExplicitAndSupportedGenerationConnectionIsOfficial() {
    env.remove("MEDIA_ASSET_SECRET_ACCESS_KEY");
    assertThrows(DependencyUnavailableException.class,() -> service().submit("owner","one","file-1","video"));
    env.put("MEDIA_ASSET_SECRET_ACCESS_KEY","asset-secret");
    when(connections.load("ark")).thenReturn(new ArkConnectionStore.Connection(1,"ark","https://zexapi.com/v1","secret"));
    assertThrows(DefinitiveRejectionException.class,() -> service().submit("owner","one","file-1","video"));
    assertTrue(actions.isEmpty());
  }
}
