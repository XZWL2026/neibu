package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:media-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","media.worker.enabled=false",
    "media.worker.poll-interval=1ms","media.worker.retry-interval=1ms"})
@AutoConfigureMockMvc
@Import(MediaApiTest.Peers.class)
class MediaApiTest {
  static final com.sun.net.httpserver.HttpServer access = startAccess();
  static com.sun.net.httpserver.HttpServer startAccess() {
    try {
      var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/internal/v1/access/authorize", exchange -> {
        var request=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        boolean valid=request.contains("sk-_"+"a".repeat(64));
        byte[] body=(valid ? "{\"ownerId\":\"owner\",\"kind\":\"API\",\"accountType\":\"API\",\"enabled\":true}" : "{}").getBytes();
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(valid?200:403,body.length);exchange.getResponseBody().write(body);exchange.close();
      });server.start();return server;
    } catch(java.io.IOException e) {throw new IllegalStateException(e);}
  }
  @org.springframework.test.context.DynamicPropertySource
  static void apiKeyProperties(org.springframework.test.context.DynamicPropertyRegistry properties) {
    properties.add("xzwl.api-key.access-url",()->"http://127.0.0.1:"+access.getAddress().getPort());
    properties.add("xzwl.api-key.service-token",()->"test-only-internal-token");
  }
  @org.junit.jupiter.api.AfterAll static void stopAccess(){access.stop(0);}
  @Test void bearerUsesProductionFilterChainAndCannotReadAnotherOwnersTask() throws Exception {
    String bearer="Bearer sk-_"+"a".repeat(64);
    mvc.perform(get("/api/v1/media/generation-options").header("Authorization",bearer)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/media/quotes").header("Authorization",bearer).contentType("application/json")
        .content("{\"type\":\"IMAGE\",\"model\":\"model\",\"resolution\":\"standard\",\"count\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("points").value("12.3400"));
    var response=mvc.perform(post("/api/v1/media/tasks").header("Authorization",bearer)
        .header("X-User-Id","forged").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
        .content("{\"type\":\"IMAGE\",\"model\":\"model\",\"prompt\":\"API task\",\"resolution\":\"standard\",\"count\":1}"))
        .andExpect(status().isAccepted()).andReturn();
    String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(get("/api/v1/media/tasks/"+id).header("Authorization",bearer)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/media/tasks/"+id).principal(()->"other")).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/media/tasks").header("Authorization","Bearer sk-_"+"b".repeat(64))).andExpect(status().isUnauthorized());
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MediaTaskWorker worker;
  @Autowired MediaTaskService service;
  @Autowired MediaTaskStore store;

  @Test void historyPagesFollowCreationTimeInsteadOfRandomIdsAndKeepTimestampTies() throws Exception {
    String owner=UUID.randomUUID().toString();
    Instant start=Instant.parse("2026-10-16T03:00:00Z");
    var oldest=historyTask(owner,owner+"-f","oldest",imageRequest(),TaskStatus.SUCCEEDED,start);
    var tiedLower=historyTask(owner,owner+"-b","tie-low",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(1));
    var tiedHigher=historyTask(owner,owner+"-c","tie-high",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(1));
    var newest=historyTask(owner,owner+"-a","newest",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(2));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("limit","2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(newest.id())).andExpect(jsonPath("$[1].id").value(tiedHigher.id()));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("limit","2")
        .param("beforeId",tiedHigher.id()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(tiedLower.id())).andExpect(jsonPath("$[1].id").value(oldest.id()));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("beforeId",oldest.id()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  }

  @Test void historyRefreshFindsNewTasksAndOlderPagesSurviveDeletionOfTheirCursor() throws Exception {
    String owner=UUID.randomUUID().toString();
    Instant start=Instant.parse("2026-10-16T04:00:00Z");
    var oldest=historyTask(owner,owner+"-f","oldest",imageRequest(),TaskStatus.SUCCEEDED,start);
    var middle=historyTask(owner,owner+"-c","middle",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(1));
    var cursor=historyTask(owner,owner+"-a","cursor",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(2));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(cursor.id()));
    var newest=historyTask(owner,owner+"-0","inserted",imageRequest(),TaskStatus.SUCCEEDED,start.plusSeconds(3));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(newest.id()));
    mvc.perform(delete("/api/v1/media/tasks/"+cursor.id()).principal(()->owner)).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("limit","2").param("beforeId",cursor.id()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(middle.id())).andExpect(jsonPath("$[1].id").value(oldest.id()));
  }

  @Test void historyRejectsUnknownAndOtherOwnersCursors() throws Exception {
    String owner=UUID.randomUUID().toString();
    var task=historyTask(owner,owner+"-cursor","cursor",imageRequest(),TaskStatus.SUCCEEDED);
    mvc.perform(get("/api/v1/media/tasks").principal(()->"another-owner").param("beforeId",task.id()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("beforeId","missing-task"))
        .andExpect(status().isNotFound());
  }

  @Test void separatesCommerceHistoryBeforePaginationAndPreservesRegenerationOrigin() throws Exception {
    String owner=UUID.randomUUID().toString();
    historyTask(owner,"isolation-1","ordinary",imageRequest(),TaskStatus.SUCCEEDED);
    historyTask(owner,"isolation-2","suite-product",imageRequest(),TaskStatus.SUCCEEDED);
    historyTask(owner,"isolation-3","model-person",imageRequest(),TaskStatus.SUCCEEDED);
    historyTask(owner,"isolation-4","commerce-video",imageRequest(),TaskStatus.SUCCEEDED);
    historyTask(owner,"isolation-5","canvas-image",imageRequest(),TaskStatus.SUCCEEDED);
    historyTask("other-owner","isolation-6","commerce-other",imageRequest(),TaskStatus.SUCCEEDED);
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("isolation-1"));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("commerceOnly","true").param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("isolation-4"));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("commerceOnly","true").param("beforeId","isolation-4"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(post("/api/v1/media/tasks/isolation-4/regenerations").principal(()->owner)
        .header("Idempotency-Key","ordinary-retry")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/media/tasks/isolation-1/regenerations").principal(()->owner)
        .header("Idempotency-Key","commerce-retry")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/media/tasks/isolation-4/regenerations").principal(()->owner)
        .header("Idempotency-Key","commerce-retry")).andExpect(status().isAccepted());
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("isolation-1"));
  }

  @Test void deletesOnlyOwnedTerminalCreatorHistoryBeforePaginationAndRetainsItsFiles() throws Exception {
    String owner=UUID.randomUUID().toString();
    var first=historyTask(owner,owner+"-1","first",imageRequest(),TaskStatus.SUCCEEDED);
    var second=historyTask(owner,owner+"-2","second",imageRequest(),TaskStatus.SUCCEEDED);
    mvc.perform(delete("/api/v1/media/tasks/"+second.id()).principal(()->"another-owner")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/media/tasks/"+second.id())).andExpect(status().isUnauthorized());
    for(int i=0;i<2;i++) mvc.perform(delete("/api/v1/media/tasks/"+second.id()).principal(()->owner)).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(first.id()));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("beforeId",second.id()).param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(first.id()));
    mvc.perform(get("/api/v1/media/tasks/"+second.id()).principal(()->owner)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/media/tasks/"+second.id()+"/results").principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].fileId").value("retained-file"));
    assertNotNull(service.findRequest(owner,"second").orElseThrow());
    mvc.perform(post("/api/v1/media/tasks/"+second.id()+"/regenerations").principal(()->owner)
        .header("Idempotency-Key","after-delete")).andExpect(status().isConflict());
  }

  @Test void regeneratesSavedImageAndVideoRequestsOnceAndRejectsOtherKeysOrSources() throws Exception {
    for(var request:List.of(imageRequest(),new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,
        "video","saved video","16:9","standard",1,5,List.of(),ReferenceMode.FIRST_LAST_FRAME))) {
      String owner=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
      var source=historyTask(owner,UUID.randomUUID().toString(),"original",request,TaskStatus.SUCCEEDED);
      String path="/api/v1/media/tasks/"+source.id()+"/regenerations";
      mvc.perform(post(path).principal(()->"another-owner").header("Idempotency-Key",key)).andExpect(status().isNotFound());
      var accepted=mvc.perform(post(path).principal(()->owner).header("Idempotency-Key",key))
          .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("SUBMIT_PENDING")).andReturn();
      String replacement=json.readTree(accepted.getResponse().getContentAsString()).path("id").asText();
      assertNotEquals(source.id(),replacement);
      assertEquals(request,service.get(owner,replacement).data().request());
      assertEquals(1,Peers.charges.get(replacement).intValue());
      Peers.unavailableOwners.add(owner);
      try {
        for(int i=0;i<2;i++) mvc.perform(post(path).principal(()->owner).header("Idempotency-Key",key))
            .andExpect(status().isAccepted()).andExpect(jsonPath("id").value(replacement));
      } finally { Peers.unavailableOwners.remove(owner); }
      assertEquals(1,Peers.charges.get(replacement).intValue());
      mvc.perform(post(path).principal(()->owner).header("Idempotency-Key","another-key")).andExpect(status().isConflict());
      mvc.perform(get("/api/v1/media/tasks").principal(()->owner))
          .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(replacement));
      mvc.perform(get("/api/v1/media/tasks/"+source.id()+"/results").principal(()->owner))
          .andExpect(jsonPath("$[0].fileId").value("retained-file"));
      var other=historyTask(owner,UUID.randomUUID().toString(),"other-source",request,TaskStatus.SUCCEEDED);
      mvc.perform(post("/api/v1/media/tasks/"+other.id()+"/regenerations").principal(()->owner).header("Idempotency-Key",key))
          .andExpect(status().isConflict());
      mvc.perform(post("/api/v1/media/tasks").principal(()->owner).header("Idempotency-Key",key)
          .contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isConflict());
    }
  }

  @Test void rejectsHistoryActionsOnRunningCanvasDepthAndRejectedAdmissionsKeepTheOriginal() throws Exception {
    String owner=UUID.randomUUID().toString();
    var running=historyTask(owner,UUID.randomUUID().toString(),"running",imageRequest(),TaskStatus.ROUTING);
    var canvas=historyTask(owner,UUID.randomUUID().toString(),"canvas-project-node",imageRequest(),TaskStatus.SUCCEEDED);
    var depthRequest=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"depth-anything-v2-small","depth","source","480p",1,5,
        List.of(new MediaReference("source",null,MediaReference.Kind.VIDEO,5.0)),null,TaskOperation.DEPTH);
    var depth=historyTask(owner,UUID.randomUUID().toString(),"depth",depthRequest,TaskStatus.SUCCEEDED);
    for(var task:List.of(running,canvas,depth)) {
      mvc.perform(delete("/api/v1/media/tasks/"+task.id()).principal(()->owner)).andExpect(status().isConflict());
      mvc.perform(post("/api/v1/media/tasks/"+task.id()+"/regenerations").principal(()->owner)
          .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    var unsupported=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,"removed-model","saved","standard",1,0,List.of());
    var original=historyTask(owner,UUID.randomUUID().toString(),"unsupported",unsupported,TaskStatus.FAILED);
    mvc.perform(post("/api/v1/media/tasks/"+original.id()+"/regenerations").principal(()->owner)
        .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity());
    var normal=service.submit(owner,"ordinary-key",imageRequest());
    var valid=historyTask(owner,UUID.randomUUID().toString(),"valid",imageRequest(),TaskStatus.SUCCEEDED);
    mvc.perform(post("/api/v1/media/tasks/"+valid.id()+"/regenerations").principal(()->owner)
        .header("Idempotency-Key","ordinary-key")).andExpect(status().isConflict());
    mvc.perform(post("/api/v1/media/tasks/"+valid.id()+"/regenerations").principal(()->owner)
        .header("Idempotency-Key","canvas-forged")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/media/tasks/"+valid.id()+"/regenerations").principal(()->owner)).andExpect(status().isBadRequest());
    assertEquals(6,service.list(owner,100,null,false).size());
    assertEquals(normal.id(),service.findRequest(owner,"ordinary-key").orElseThrow().id());
  }

  @Test void concurrentDoubleClicksCreateOnlyOneReplacementAndOneCharge() throws Exception {
    String owner=UUID.randomUUID().toString();
    for(boolean sameKey:List.of(true,false)) {
      var original=historyTask(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),imageRequest(),TaskStatus.SUCCEEDED);
      String key=UUID.randomUUID().toString();
      try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var gate=new java.util.concurrent.CountDownLatch(1);
        var calls=new ArrayList<java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult>>();
        for(int i=0;i<2;i++) {
          String requestKey=sameKey ? key : key+"-"+i;
          calls.add(pool.submit(()-> { gate.await();return mvc.perform(post("/api/v1/media/tasks/"+original.id()+"/regenerations")
              .principal(()->owner).header("Idempotency-Key",requestKey)).andReturn(); }));
        }
        gate.countDown();
        Set<String> replacements=new HashSet<>(); int conflicts=0;
        for(var call:calls) {
          var response=call.get().getResponse();
          if(response.getStatus()==409) conflicts++;
          else { assertEquals(202,response.getStatus());replacements.add(json.readTree(response.getContentAsString()).path("id").asText()); }
        }
        assertEquals(sameKey?0:1,conflicts); assertEquals(1,replacements.size());
        String replacement=replacements.iterator().next();
        assertEquals(1,Peers.charges.get(replacement).intValue());
      }
    }
  }

  private TaskRequest imageRequest() {
    return new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,"model","saved prompt","16:9","standard",2,0,
        List.of(new MediaReference("saved-reference",null)),null);
  }
  private MediaTask historyTask(String owner,String id,String key,TaskRequest request,TaskStatus status) {
    return historyTask(owner,id,key,request,status,Instant.now());
  }
  private MediaTask historyTask(String owner,String id,String key,TaskRequest request,TaskStatus status,Instant now) {
    var task=store.create(new MediaTask(id,owner,key,status==TaskStatus.SUCCEEDED?TaskStatus.ARCHIVING:status,
        new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(600),now,0));
    if(status==TaskStatus.SUCCEEDED) {
      var lease=store.claim(id,"fixture",now,now.plusSeconds(30)).orElseThrow();
      store.save(lease,task.advance(status,task.data(),now),List.of(new TaskResult(id,0,null,"retained-file")),now);
    }
    return service.get(owner,id);
  }

  @Test void creatorHistoryExcludesCanvasBeforePaginationWithoutHidingOwnedTaskDetails() throws Exception {
    String owner=UUID.randomUUID().toString();
    var image=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,"model","history image","1024",1,0,List.of());
    var video=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"video","history video","16:9","480p",1,4,List.of(),null);
    var now=Instant.now();
    for(int i=1;i<=5;i++) {
      String key=i==2 || i==4 ? UUID.randomUUID().toString() : "canvas-project-node-"+i;
      store.create(new MediaTask(owner+"-"+i,owner,key,TaskStatus.ROUTING,
          new TaskData(i==2 || i==3?video:image,null,null,null,null,null,null),now,now.plusSeconds(600),now,0));
    }
    store.create(new MediaTask(owner+"-other","other-owner","regular",TaskStatus.ROUTING,
        new TaskData(image,null,null,null,null,null,null),now,now.plusSeconds(600),now,0));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","false"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(owner+"-4"));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("limit","1").param("beforeId",owner+"-4"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(owner+"-2"));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner).param("excludeCanvas","true").param("beforeId",owner+"-2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/v1/media/tasks/"+owner+"-5").principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("id").value(owner+"-5"));
    mvc.perform(get("/api/v1/media/tasks/"+owner+"-5").principal(()->"other-owner"))
        .andExpect(status().isNotFound());
  }

  @Test void localDepthIsDisabledByDefaultAndGeneralSubmissionCannotBypassAdmission() throws Exception {
    mvc.perform(get("/api/v1/media/depth-options").principal(()->"owner"))
        .andExpect(status().isOk()).andExpect(jsonPath("enabled").value(false))
        .andExpect(jsonPath("highResolutionMaxDurationSeconds").value(30));
    mvc.perform(post("/api/v1/media/depth-tasks").principal(()->"owner").header("Idempotency-Key","depth-disabled")
        .contentType("application/json").content("{\"sourceFileId\":\"source\",\"resolution\":\"480p\"}"))
        .andExpect(status().isServiceUnavailable());
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"depth-anything-v2-small","depth","source","480p",1,31,
        List.of(new MediaReference("source",null,MediaReference.Kind.VIDEO,30.1)),null,TaskOperation.DEPTH);
    mvc.perform(post("/api/v1/media/tasks").principal(()->"owner").header("Idempotency-Key","bypass-depth")
        .contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/media/depth-tasks").principal(()->"owner").header("Idempotency-Key","forge-duration")
        .contentType("application/json").content("{\"sourceFileId\":\"source\",\"resolution\":\"720p\",\"durationSeconds\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test void portraitSubmissionRejectsUnknownPropertiesWithTheProductionJacksonConfiguration() throws Exception {
    mvc.perform(post("/api/v1/media/portraits").principal(() -> "owner").header("Idempotency-Key",UUID.randomUUID().toString())
        .contentType("application/json").content("{\"fileId\":\"file-1\",\"model\":\"video\",\"assetId\":\"untrusted\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test void submittedPortraitTaskReplaysOriginalReceiptBeforeChangedReviewOrCatalogValidation() throws Exception {
    String key=UUID.randomUUID().toString();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"unavailable-after-rotation","portrait task","16:9","720p",1,5,
        List.of(new MediaReference("file-portrait",null,MediaReference.Kind.IMAGE,null,"portrait-previously-approved")),ReferenceMode.OMNI);
    var task=service.submit("owner",key,request);
    mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",key)
        .contentType("application/json").content(json.writeValueAsString(request)))
        .andExpect(status().isAccepted()).andExpect(jsonPath("id").value(task.id())).andExpect(jsonPath("status").value("ROUTING"));
    assertEquals(TaskStatus.ROUTING,service.get("owner",task.id()).status());
    var different=new TaskRequest(request.type(),"different",request.prompt(),request.aspectRatio(),request.resolution(),1,5,request.references(),ReferenceMode.OMNI);
    mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",key)
        .contentType("application/json").content(json.writeValueAsString(different))).andExpect(status().isConflict());
  }

  @Test void submitsQueriesAndReturnsOnlyArchivedFileIdsForImagesAndVideos() throws Exception {
    for(String type:List.of("IMAGE","VIDEO")) {
      String body="{\"type\":\""+type+"\",\"model\":\"model\",\"prompt\":\"A cloud\",\"resolution\":\"standard\",\"count\":1,\"durationSeconds\":"+(type.equals("VIDEO")?5:0)+",\"references\":[]}";
      if(type.equals("VIDEO")) body=body.replace("\"model\":\"model\"", "\"model\":\"video\"");
      String key=UUID.randomUUID().toString();
      var response=mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner")
          .header("Idempotency-Key",key).contentType("application/json").content(body))
          .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("SUBMIT_PENDING")).andReturn();
      String id=json.readTree(response.getResponse().getContentAsString()).get("id").asText();
      for(int i=0;i<8;i++) { worker.process(id); Thread.sleep(5); }
      mvc.perform(get("/api/v1/media/tasks/"+id).principal(() -> "owner"))
          .andExpect(status().isOk()).andExpect(jsonPath("status").value("SUCCEEDED"))
          .andExpect(jsonPath("results[0].fileId").value("stored-file"))
          .andExpect(jsonPath("prompt").value("A cloud"))
          .andExpect(jsonPath("references").isArray())
          .andExpect(jsonPath("refundStatus").value("NOT_REQUIRED"))
          .andExpect(jsonPath("providerEndpoint").doesNotExist()).andExpect(jsonPath("results[0].sourceUrl").doesNotExist());
      mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",key)
          .contentType("application/json").content(body)).andExpect(jsonPath("id").value(id));
      mvc.perform(get("/api/v1/media/tasks/"+id).principal(() -> "other")).andExpect(status().isNotFound());
    }
  }

  @Test void projectsPartialFailureAndConfirmsRefundOnlyAfterTheWorkerCompletesIt() throws Exception {
    String id=UUID.randomUUID().toString(); Instant now=Instant.now();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,"model","partial snapshot","standard",2,0,List.of());
    var charge=new ChargeReceipt("charge-test",new BigDecimal("12.0000"),BigDecimal.ONE,new BigDecimal("12.0000"));
    var data=new TaskData(request,null,charge,null,new BigDecimal("6.0000"),TaskStatus.PARTIAL_SUCCESS,"PARTIAL_PROVIDER_OUTPUT")
        .withPresentation(new TaskData.Presentation("Original Model Name",false));
    var task=store.create(new MediaTask(id,"owner",id,TaskStatus.REFUND_PENDING,data,now,now.plusSeconds(60),now,0));
    var lease=store.claim(id,"test-lease",now,now.plusSeconds(30)).orElseThrow();
    store.save(lease,task.advance(TaskStatus.REFUND_PENDING,data,now),List.of(new TaskResult(id,0,null,"stored-partial"),new TaskResult(id,1,null,null,"ARCHIVE_REJECTED")),now);
    mvc.perform(get("/api/v1/media/tasks/"+id).principal(()->"owner"))
        .andExpect(status().isOk()).andExpect(jsonPath("status").value("REFUND_PENDING"))
        .andExpect(jsonPath("results[0].fileId").value("stored-partial"))
        .andExpect(jsonPath("failures[0].index").value(1))
        .andExpect(jsonPath("failures[0].message").value("生成已完成，但结果文件保存失败；请联系管理员检查存储配置与结果地址"))
        .andExpect(jsonPath("refundStatus").value("PENDING"))
        .andExpect(jsonPath("modelName").value("Original Model Name"))
        .andExpect(jsonPath("failureCode").doesNotExist()).andExpect(jsonPath("providerFailure").doesNotExist());
    worker.process(id);
    mvc.perform(get("/api/v1/media/tasks/"+id).principal(()->"owner"))
        .andExpect(jsonPath("status").value("PARTIAL_SUCCESS"))
        .andExpect(jsonPath("refundStatus").value("COMPLETED"))
        .andExpect(jsonPath("results[0].fileId").value("stored-partial"));
    assertEquals("PARTIAL_PROVIDER_OUTPUT",service.get("owner",id).data().failureCode());
  }

  @Autowired com.xzwl.cloud.media.interfaces.http.MediaTaskController taskViews;
  @Test void adminTaskSnapshotRequiresRoleAndNeverExposesProviderOrReferenceUrls() throws Exception {
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new com.xzwl.cloud.media.interfaces.http.MediaAdminTaskController(store,taskViews,(owner,file)->new TaskFileAccess.Access("https://signed.example/file",Instant.now().plusSeconds(60)))).build();
    String id=UUID.randomUUID().toString();Instant now=Instant.now();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.IMAGE,"model","original prompt","standard",1,0,List.of(new MediaReference("ref-file",null),new MediaReference(null,"https://example.com/reference.png")));
    store.create(new MediaTask(id,"owner",id,TaskStatus.ROUTING,new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(60),now,0));
    String path="/api/v1/admin/media/tasks/"+id;
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).principal(()->"owner")).andExpect(status().isForbidden());
    mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole("NORMAL_ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(jsonPath("ownerId").value("owner"))
        .andExpect(jsonPath("task.prompt").value("original prompt"))
        .andExpect(jsonPath("references[0].fileId").value("ref-file"))
        .andExpect(jsonPath("references[0].kind").value("IMAGE"))
        .andExpect(jsonPath("references[1].fileId").isEmpty())
        .andExpect(jsonPath("task.references").isEmpty())
        .andExpect(result->assertFalse(result.getResponse().getContentAsString().contains("https://")));
    mvc.perform(get(path+"/files/unrelated/access-url").principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}))
        .andExpect(status().isNotFound());
  }

  @Test void adminReferenceKindsComeFromEachStoredReference() throws Exception {
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new com.xzwl.cloud.media.interfaces.http.MediaAdminTaskController(store,taskViews,(owner,file)->{throw new AssertionError("snapshot must not sign files");})).build();
    String id=UUID.randomUUID().toString();Instant now=Instant.now();
    var refs=List.of(new MediaReference("picture",null),new MediaReference("clip",null,MediaReference.Kind.VIDEO,5.0),new MediaReference("sound",null,MediaReference.Kind.AUDIO,5.0));
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"model","prompt","16:9","720p",1,5,refs,com.xzwl.cloud.media.domain.task.ReferenceMode.OMNI);
    store.create(new MediaTask(id,"owner",id,TaskStatus.ROUTING,new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(60),now,0));
    mvc.perform(get("/api/v1/admin/media/tasks/"+id).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(jsonPath("references[0].kind").value("IMAGE"))
        .andExpect(jsonPath("references[1].kind").value("VIDEO")).andExpect(jsonPath("references[1].fileId").value("clip"))
        .andExpect(jsonPath("references[2].kind").value("AUDIO")).andExpect(jsonPath("task.references").isEmpty());
  }

  @Test void administratorCanAccessRetainedNativeFileDuringFailedEnhancementButUserCannot() throws Exception {
    String id=UUID.randomUUID().toString();Instant now=Instant.now();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"model","prompt","480p",1,5,List.of());
    store.create(new MediaTask(id,"owner",id,TaskStatus.FAILED,new TaskData(request,null,null,null,null,BigDecimal.ZERO,TaskStatus.FAILED,"PROVIDER_FAILED"),now,now.plusSeconds(60),now,0));
    var provider=org.mockito.Mockito.mock(ProviderPort.class);
    org.mockito.Mockito.when(provider.diagnostics(id)).thenReturn(new ProviderPort.Diagnostics(
        new ProviderPort.Stage("火山","native-upstream","COMPLETE","480p",1000L,123L,null,null,null,null,List.of(new ProviderPort.DiagnosticOutput(0,"retained-native",null))),
        new ProviderPort.Stage("腾讯云 MPS","enhance-upstream","FAILED","720p",2000L,null,null,720L,"MPS_RUNNING","MediaProcessFailed",List.of())));
    org.mockito.Mockito.when(provider.failure(id)).thenReturn(new ProviderPort.FailureDetail("MPS_RUNNING","MediaProcessFailed"));
    var views=new com.xzwl.cloud.media.interfaces.http.MediaTaskController(service,worker,null,provider,json);
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new com.xzwl.cloud.media.interfaces.http.MediaAdminTaskController(store,views,(owner,file)->{
          assertEquals("owner",owner);assertEquals("retained-native",file);
          return new TaskFileAccess.Access("https://signed.example/native",now.plusSeconds(60));
        })).build();
    String path="/api/v1/admin/media/tasks/"+id;
    mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(jsonPath("generation.taskId").value("native-upstream"))
        .andExpect(jsonPath("enhancement.taskId").value("enhance-upstream"))
        .andExpect(jsonPath("task.failureMessage").value("MPS_RUNNING · MediaProcessFailed"));
    mvc.perform(get(path+"/files/retained-native/access-url").principal(()->"owner")).andExpect(status().isForbidden());
    mvc.perform(get(path+"/files/retained-native/access-url").principal(()->"admin").with(r->{r.addUserRole("NORMAL_ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(jsonPath("url").value("https://signed.example/native"))
        .andExpect(header().string("Cache-Control","no-store"));
    mvc.perform(get(path+"/files/not-associated/access-url").principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isNotFound());
  }
  @Test void scheduledStepsUseFinalFileAndAuthorizeOnlyTheirOwnIntermediateFile() throws Exception {
    String id=UUID.randomUUID().toString();Instant now=Instant.now();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"model","prompt","1080p",1,5,List.of());
    store.create(new MediaTask(id,"owner",id,TaskStatus.SUCCEEDED,new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(60),now,0));
    var provider=org.mockito.Mockito.mock(ProviderPort.class);
    org.mockito.Mockito.when(provider.diagnostics(id)).thenReturn(new ProviderPort.Diagnostics(null,
        new ProviderPort.Stage("腾讯云 MPS","schedule-task","COMPLETE","1080p",81988L,null,"108307",null,null,null,List.of(),List.of(
            new ProviderPort.EnhancementStep(0,10,2560,1440,"COMPLETE",65000L,"intermediate-file",null),
            new ProviderPort.EnhancementStep(1,20,1920,1080,"COMPLETE",72000L,null,null)))));
    var taskStore=org.mockito.Mockito.mock(MediaTaskStore.class);
    org.mockito.Mockito.when(taskStore.find(id)).thenReturn(store.find(id));
    org.mockito.Mockito.doReturn(List.of(new TaskResult(id,0,null,"final-file"))).when(taskStore).results(id);
    var views=new com.xzwl.cloud.media.interfaces.http.MediaTaskController(service,worker,null,provider,json);
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new com.xzwl.cloud.media.interfaces.http.MediaAdminTaskController(taskStore,views,(owner,file)->{
          assertEquals("owner",owner);assertTrue(List.of("intermediate-file","final-file").contains(file));
          return new TaskFileAccess.Access("https://signed.example/video",now.plusSeconds(60));
        })).build();
    String path="/api/v1/admin/media/tasks/"+id;
    mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(jsonPath("enhancement.steps[0].fileId").value("intermediate-file"))
        .andExpect(jsonPath("enhancement.steps[1].fileId").value("final-file"))
        .andExpect(jsonPath("enhancement.steps[0].durationMs").value(65000));
    for(String file:List.of("intermediate-file","final-file")) {
      mvc.perform(get(path+"/files/"+file+"/access-url").principal(()->"owner")).andExpect(status().isForbidden());
      mvc.perform(get(path+"/files/"+file+"/access-url").principal(()->"admin").with(r->{r.addUserRole("NORMAL_ADMIN");return r;}))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    mvc.perform(get(path+"/files/other-task-file/access-url").principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isNotFound());
  }

  @Test void restoresHistoricalModelNameFromItsRouteSnapshot() throws Exception {
    String id=UUID.randomUUID().toString(); Instant now=Instant.now();
    var request=new TaskRequest(com.xzwl.cloud.media.domain.generation.MediaType.VIDEO,"video","old prompt","standard",1,5,List.of());
    var route=new RouteSnapshot(id,"video","ark","Provider","https://provider.example","upstream","{}","{\"displayName\":\"提交时旧模型\"}",now);
    var data=new TaskData(request,route,null,null,BigDecimal.ZERO,TaskStatus.FAILED,"PROVIDER_FAILED");
    store.create(new MediaTask(id,"owner",id,TaskStatus.FAILED,data,now,now.plusSeconds(60),now,0));
    mvc.perform(get("/api/v1/media/tasks/"+id).principal(()->"owner"))
        .andExpect(status().isOk()).andExpect(jsonPath("modelName").value("提交时旧模型"))
        .andExpect(jsonPath("prompt").value("old prompt"));
  }

  @Test void rejectsAnonymousConflictingAndEmbeddedMediaRequests() throws Exception {
    mvc.perform(get("/api/v1/media/tasks")).andExpect(status().isUnauthorized());
    String body="{\"type\":\"IMAGE\",\"model\":\"model\",\"prompt\":\"prompt\",\"resolution\":\"1024\",\"count\":1,\"references\":[]}";
    String key=UUID.randomUUID().toString();
    mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",key)
        .contentType("application/json").content(body)).andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",key)
        .contentType("application/json").content(body.replace("prompt\"}","changed\"}" ).replace(":\"prompt\"",":\"different\"")))
        .andExpect(status().isConflict());
    for(String bad:List.of(body.replace("[]","[{\"httpsUrl\":\"data:image/png;base64,AAAA\"}]"),
        body.replace("[]","[{\"fileId\":\"AAAA==\"}]"),body.replace("\"references\":[]","\"imageBase64\":\"AAAA\""))) {
      mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",UUID.randomUUID().toString())
          .contentType("application/json").content(bad)).andExpect(status().isBadRequest());
    }
  }

  @Test void generationOptionsAndQuotesRequireLoginAndRejectUnsupportedSelectionBeforeCreatingTasks() throws Exception {
    mvc.perform(get("/api/v1/media/generation-options")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/media/quotes").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/media/generation-options").principal(() -> "owner"))
        .andExpect(status().isOk()).andExpect(jsonPath("models[0].model").value("model"));
    String quote="{\"type\":\"IMAGE\",\"model\":\"model\",\"aspectRatio\":\"1:1\",\"resolution\":\"standard\",\"count\":1}";
    mvc.perform(post("/api/v1/media/quotes").principal(() -> "owner").contentType("application/json").content(quote))
        .andExpect(status().isOk()).andExpect(jsonPath("points").value("12.3400"));
    String invalid=quote.replace("standard","unsupported");
    int before=service.list("owner",100,null,false).size();
    mvc.perform(post("/api/v1/media/quotes").principal(() -> "owner").contentType("application/json").content(invalid))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/v1/media/tasks").principal(() -> "owner").header("Idempotency-Key",UUID.randomUUID().toString())
        .contentType("application/json").content(invalid.replace("}",",\"prompt\":\"cloud\"}")))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(before,service.list("owner",100,null,false).size());
  }

  @TestConfiguration static class Peers {
    static final Map<String,Integer> charges=new java.util.concurrent.ConcurrentHashMap<>();
    static final Set<String> unavailableOwners=java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Bean @Primary RoutingPort testRouting() { return new RoutingPort() {
      public RouteSnapshot route(String id,String model) { return new RouteSnapshot(id,model,"provider","Provider","https://provider.example","upstream","{}","{}",Instant.now()); }
      public List<Model> models() { return List.of(
          new Model("model","upstream",Map.of("type","IMAGE","displayName","Image","resolutions",List.of("standard","1024"),
              "aspectRatios",List.of("1:1","16:9"),"maxCount",4,"minDurationSeconds",0,"maxDurationSeconds",0)),
          new Model("video","upstream",Map.of("type","VIDEO","displayName","Video","resolutions",List.of("standard"),
              "aspectRatios",List.of("16:9"),"maxCount",1,"minDurationSeconds",5,"maxDurationSeconds",15))); }
    }; }
    @Bean @Primary BillingPort testBilling() { return new BillingPort() {
      public BigDecimal quote(String owner,TaskRequest request) { return new BigDecimal("12.3400"); }
      public List<AvailablePrice> availablePrices(String owner) { if(unavailableOwners.contains(owner)) return List.of(); return List.of(
          new AvailablePrice("model","standard",BigDecimal.ONE),new AvailablePrice("model","1024",BigDecimal.ONE),
          new AvailablePrice("video","standard",BigDecimal.ONE)); }
      public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) { charges.merge(id,1,Integer::sum); return new ChargeReceipt(id,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN); }
      public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) {}
    }; }
    @Bean @Primary ProviderPort testProvider(MediaTaskStore store) { return new ProviderPort() {
      public void requireAvailable(RouteSnapshot route) {}
      public String submit(String id,String owner,TaskRequest request,RouteSnapshot route) { assertEquals(store.find(id).orElseThrow().ownerId(),owner); return id; }
      public ProviderPoll poll(String id,TaskRequest request,RouteSnapshot route) { return ProviderPoll.completed(List.of(new ProviderOutput(0,"https://cdn.example/result"))); }
    }; }
    @Bean @Primary StoragePort testStorage() { return new StoragePort() {
      public java.util.List<MediaReference> resolveReferences(String owner, java.util.List<MediaReference> references) { return references; }
      public String archive(String owner,String id,int index,String url) { return "stored-file"; }
    }; }
  }
}
