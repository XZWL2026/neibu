package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the real HTTP, quote persistence, task admission and worker against H2. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:enhancement-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","media.worker.enabled=false",
    "media.worker.poll-interval=1ms","media.worker.retry-interval=1ms"})
@AutoConfigureMockMvc
@Import(EnhancementApiTest.Peers.class)
class EnhancementApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MediaTaskStore store;
  @Autowired MediaTaskWorker worker;
  @Autowired JdbcTemplate jdbc;

  String source(String owner) {
    return source(owner,"720p");
  }
  String source(String owner,String resolution) {
    String id=UUID.randomUUID().toString();
    var request=new TaskRequest(MediaType.VIDEO,"video","saved video","16:9",resolution,1,5,List.of());
    Instant now=Instant.now();
    store.create(new MediaTask(id,owner,id,TaskStatus.SUCCEEDED,new TaskData(request,null,null,null,null,null,null),now,now.plusSeconds(600),now,0));
    jdbc.update("INSERT INTO media_task_result(id,task_id,output_index,file_id) VALUES(?,?,0,?)",id+":0",id,"source-"+id);
    Peers.sourceSizes.put("source-"+id,"1080p".equals(resolution)?new int[]{1920,1080}:new int[]{1280,720});
    return id;
  }

  String quote(String owner,String source) throws Exception {
    var response=mvc.perform(get("/api/v1/media/tasks/"+source+"/outputs/0/enhancement-options").principal(()->owner))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("billableSeconds").value(6))
        .andExpect(jsonPath("targets[0].targetResolution").value("1080p"))
        .andExpect(jsonPath("targets[0].points").value("15.0000")).andReturn();
    return json.readTree(response.getResponse().getContentAsString()).path("targets").get(0).path("quoteId").asText();
  }

  String enhance(String owner,String source,String quote,String key) throws Exception {
    var response=mvc.perform(post("/api/v1/media/tasks/"+source+"/outputs/0/enhancements").principal(()->owner)
        .header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(Map.of("quoteId",quote))))
        .andExpect(status().isAccepted()).andExpect(jsonPath("operation").value("ENHANCEMENT")).andReturn();
    return json.readTree(response.getResponse().getContentAsString()).path("id").asText();
  }

  void finish(String id) throws Exception {
    for(int i=0;i<12 && !store.find(id).orElseThrow().status().terminal();i++) {
      Thread.sleep(5); worker.process(id);
    }
  }

  @Test void restoresPaidEnhancementWithOneChargeAndKeepsVersionsInTheirSourceRecord() throws Exception {
    String owner=UUID.randomUUID().toString(), source=source(owner), quote=quote(owner,source), key=UUID.randomUUID().toString();
    String child=enhance(owner,source,quote,key);
    assertEquals(child,enhance(owner,source,quote,key));
    assertEquals(1,Peers.charges.get(child));
    mvc.perform(delete("/api/v1/media/tasks/"+source).principal(()->owner)).andExpect(status().isConflict());
    finish(child);
    assertEquals(TaskStatus.SUCCEEDED,store.find(child).orElseThrow().status());
    mvc.perform(get("/api/v1/media/tasks/"+source).principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("status").value("SUCCEEDED"))
        .andExpect(jsonPath("results[0].fileId").value("source-"+source))
        .andExpect(jsonPath("enhancements[0].results[0].fileId").value("enhanced-"+child));
    mvc.perform(get("/api/v1/media/tasks").principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(source));
    assertEquals(child,enhance(owner,source,quote(owner,source),UUID.randomUUID().toString()));
    assertEquals(1,Peers.charges.get(child));
    mvc.perform(delete("/api/v1/media/tasks/"+source).principal(()->owner)).andExpect(status().isNoContent());
  }

  @Test void rejectsAnotherOwnersSourceAndAnEnhancementAsSourceWithoutCharging() throws Exception {
    String owner=UUID.randomUUID().toString(), source=source(owner);
    mvc.perform(get("/api/v1/media/tasks/"+source+"/outputs/0/enhancement-options").principal(()->"another-owner"))
        .andExpect(status().isNotFound());
    String child=enhance(owner,source,quote(owner,source),UUID.randomUUID().toString());
    finish(child);
    mvc.perform(get("/api/v1/media/tasks/"+child+"/outputs/0/enhancement-options").principal(()->owner))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(1,Peers.charges.get(child));
  }

  @Test void refundsOnlyFailedEnhancementAndRetainsTheSuccessfulSource() throws Exception {
    String owner=UUID.randomUUID().toString(), source=source(owner);
    String child=enhance(owner,source,quote(owner,source),UUID.randomUUID().toString());
    Peers.failures.add(child);
    finish(child);
    assertEquals(TaskStatus.FAILED,store.find(child).orElseThrow().status());
    assertEquals(new BigDecimal("15.0000"),Peers.refunds.get(child+"-refund"));
    mvc.perform(get("/api/v1/media/tasks/"+source).principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("status").value("SUCCEEDED"))
        .andExpect(jsonPath("enhancements[0].refundStatus").value("COMPLETED"));
    mvc.perform(delete("/api/v1/media/tasks/"+source).principal(()->owner)).andExpect(status().isNoContent());
  }

  @Test void twoKQuoteCreatesAnIndependentPaidVersionAndDoesNotOfferEqualOrLowerTargets() throws Exception {
    String owner=UUID.randomUUID().toString(), source=source(owner,"1080p");
    var response=mvc.perform(get("/api/v1/media/tasks/"+source+"/outputs/0/enhancement-options").principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("targets.length()").value(1))
        .andExpect(jsonPath("targets[0].targetResolution").value("2K")).andReturn();
    String quote=json.readTree(response.getResponse().getContentAsString()).path("targets").get(0).path("quoteId").asText();
    String child=enhance(owner,source,quote,UUID.randomUUID().toString());
    finish(child);
    assertEquals("2K",store.find(child).orElseThrow().data().request().resolution());
    assertEquals(TaskStatus.SUCCEEDED,store.find(child).orElseThrow().status());
    assertEquals(1,Peers.charges.get(child));
    mvc.perform(get("/api/v1/media/tasks/"+source).principal(()->owner))
        .andExpect(status().isOk()).andExpect(jsonPath("enhancements[0].targetResolution").value("2K"));
  }

  @TestConfiguration static class Peers {
    static final Map<String,Integer> charges=new HashMap<>();
    static final Map<String,BigDecimal> refunds=new HashMap<>();
    static final Set<String> failures=new HashSet<>();
    static final Map<String,int[]> sourceSizes=new HashMap<>();
    @Bean @Primary BillingPort enhancementBilling() { return new BillingPort() {
      public List<AvailablePrice> availablePrices(String owner) { return List.of(new AvailablePrice("video-enhancement","1080p",BigDecimal.ONE,"ENHANCEMENT"),new AvailablePrice("video-enhancement","2K",BigDecimal.ONE,"ENHANCEMENT")); }
      public EnhancementCost enhancementQuote(String owner,String target,int seconds) { return new EnhancementCost(new BigDecimal("1.5000"),new BigDecimal("10.0000"),new BigDecimal("15.0000")); }
      public ChargeReceipt chargeEnhancement(String id,String owner,String target,int seconds,EnhancementCost cost) {
        charges.merge(id,1,Integer::sum); return new ChargeReceipt(id,cost.amount(),cost.rate(),cost.points());
      }
      public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) { throw new AssertionError("enhancement must not charge generation"); }
      public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) { refunds.put(id,points); }
    }; }
    @Bean @Primary StoragePort enhancementStorage() { return new StoragePort() {
      public List<MediaReference> resolveReferences(String owner,List<MediaReference> refs) { return refs; }
      public VideoSource probeVideo(String owner,String file) { var size=sourceSizes.get(file);return new VideoSource("https://assets.example/source.mp4","video/mp4",5.03,size[0],size[1]); }
      public String archive(String owner,String task,int index,String url) { return "enhanced-"+task; }
    }; }
    @Bean @Primary EnhancementProvider testEnhancementProvider() { return new EnhancementProvider() {
      public void prepare(String id,EnhancementQuoteStore.Quote quote) {}
      public String submit(MediaTask task,EnhancementQuoteStore.Quote quote) { return task.id(); }
      public ProviderPort.ProviderPoll poll(MediaTask task) { return failures.contains(task.id()) ? ProviderPort.ProviderPoll.failed()
          : ProviderPort.ProviderPoll.completed(List.of(new ProviderPort.ProviderOutput(0,"https://assets.example/enhanced.mp4"))); }
    }; }
    @Bean @Primary MpsConfigurationStore enhancementConfiguration() { return new MpsConfigurationStore() {
      public Configuration load() { return new Configuration(1,"","","","","","",0,0,0,List.of(new EnhancementTemplate("hd","1080p",List.of("720p"),true,null,1L,Map.of()),new EnhancementTemplate("2k","2K",List.of("480p","720p","1080p"),true,null,2L,Map.of())),List.of()); }
      public Configuration save(Configuration requested) { throw new UnsupportedOperationException(); }
    }; }
    @Bean @Primary MpsConfigurationProbe enhancementProbe() { return new MpsConfigurationProbe() {
      public List<MpsConfigurationStore.EnhancementTemplate> templates(MpsConfigurationStore.Configuration config) { return config.enhancementTemplates(); }
      public Result test(long version,String template,String ratio) { return "2k".equals(template)?new Result(version,true,"",0,2560,1440,List.of(2L),null,""):new Result(version,true,"",0,1920,1080,List.of(1L),null,""); }
    }; }
  }
}
