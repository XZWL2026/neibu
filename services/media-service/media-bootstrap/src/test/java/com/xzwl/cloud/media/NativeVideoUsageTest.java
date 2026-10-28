package com.xzwl.cloud.media;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:native-usage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","media.worker.enabled=false"})
@AutoConfigureMockMvc(addFilters=false)
class NativeVideoUsageTest {
  static final String PATH="/api/v1/admin/media/native-video-usage";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @BeforeEach void reset() {jdbc.update("DELETE FROM media_provider_operation");jdbc.update("DELETE FROM media_task");}

  @Test void groupsByBeijingCompletionDayWithMutuallyExclusiveNativeAndEnhancementBuckets() throws Exception {
    add("2026-09-10T16:00:00Z","480p","480p",5,"COMPLETE",null,"seedance-2.5");
    add("2026-09-11T15:59:59.999Z"," 480P ","720p",8,"FAILED","MPS_RUNNING","seedance-2.5");
    add("2026-09-11T00:00:00Z","720p","1080p",10,"MPS_READY",null,"seedance-2.5");
    add("2026-09-11T00:00:00Z","1080p","1080p",12,"COMPLETE",null,"seedance-2.5");
    add("2026-09-11T00:00:00Z","480p","720p",4,"MPS_RUNNING",null," Seedance 2.0 Fast ");
    add("2026-09-11T16:00:00Z","480p","480p",6,"COMPLETE",null,"seedance-2.5");
    add("2026-09-10T15:59:59.999Z","480p","480p",99,"COMPLETE",null,"seedance-2.5");
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(2))
        .andExpect(jsonPath("total.tasks").value(5)).andExpect(jsonPath("total.seconds").value(39))
        .andExpect(jsonPath("items[1].date").value("2026-09-11"))
        .andExpect(jsonPath("items[1].model").value("Seedance 2.5"))
        .andExpect(jsonPath("items[1].native480.tasks").value(1)).andExpect(jsonPath("items[1].native480.seconds").value(5))
        .andExpect(jsonPath("items[1].native720.seconds").value(0)).andExpect(jsonPath("items[1].native1080.seconds").value(12))
        .andExpect(jsonPath("items[1].enhanced1080.seconds").value(10))
        .andExpect(jsonPath("items[1].enhanced720.tasks").value(1))
        .andExpect(jsonPath("items[1].enhanced720.seconds").value(8))
        .andExpect(jsonPath("items[1].total.tasks").value(4));
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("NORMAL_ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-12"))
        .andExpect(status().isOk()).andExpect(jsonPath("total.tasks").value(6))
        .andExpect(jsonPath("items[0].date").value("2026-09-12"));
  }

  @Test void assignsEveryResolutionPairToOneBucketWhoseSumEqualsTheTotal() throws Exception {
    String at="2026-09-11T00:00:00Z";
    String[][] pairs={{"480p","480p","5"},{"720p","720p","8"},{"1080p","1080p","10"},
        {"480p"," 720P ","4"},{"480p","1080p","6"},{"720p","1080p","7"},
        {"480p","4K","9"},{"720p","4k","11"},{"1080p","4k","12"}};
    for(var pair:pairs)add(at,pair[0],pair[1],Integer.parseInt(pair[2]),"COMPLETE",null,"seedance-2.5");
    var response=mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("items[0].native480.seconds").value(5))
        .andExpect(jsonPath("items[0].native720.seconds").value(8))
        .andExpect(jsonPath("items[0].native1080.seconds").value(10))
        .andExpect(jsonPath("items[0].enhanced720.seconds").value(4))
        .andExpect(jsonPath("items[0].enhanced1080.seconds").value(13))
        .andExpect(jsonPath("items[0].enhanced4k.seconds").value(32))
        .andExpect(jsonPath("total.tasks").value(9)).andExpect(jsonPath("total.seconds").value(72))
        .andReturn().getResponse().getContentAsString();
    var item=json.readTree(response).path("items").get(0);
    long tasks=0,seconds=0;
    for(String field:List.of("native480","native720","native1080","enhanced720","enhanced1080","enhanced4k")) {
      tasks+=item.path(field).path("tasks").asLong();seconds+=item.path(field).path("seconds").asLong();
    }
    Assertions.assertEquals(item.path("total").path("tasks").asLong(),tasks);
    Assertions.assertEquals(item.path("total").path("seconds").asLong(),seconds);
  }

  @Test void excludesFailuresOtherProvidersAndMissingMetadataWithoutGuessingDefaults() throws Exception {
    String at="2026-09-11T00:00:00Z";
    add(at,"480p","720p",5,"FAILED","ARK_RUNNING","seedance-2.5");
    add(at,null,"480p",5,"COMPLETE",null,"seedance-2.5");
    add(at,"4k","480p",5,"COMPLETE",null,"seedance-2.5");
    add(at,"480p","480p",5,"COMPLETE",null,"unknown-model");
    add(at,"480p","480p",0,"COMPLETE",null,"seedance-2.5");
    String id=add(at,"480p","720p",5,"COMPLETE",null,"seedance-2.5");
    jdbc.update("UPDATE media_provider_operation SET plan_json=REPLACE(plan_json,'ark.cn-beijing.volces.com','zexapi.com') WHERE operation_id=?",id);
    for(String change:List.of("depth","image","missingDuration","missingType","unknownOperation","invalidJson")) {
      id=add(at,"480p","720p",5,"COMPLETE",null,"seedance-2.5");
      var tree=json.readTree(jdbc.queryForObject("SELECT task_json FROM media_task WHERE id=?",String.class,id));
      var request=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data").path("request");
      switch(change) {
        case "depth" -> request.put("operation","DEPTH");
        case "image" -> request.put("type","IMAGE");
        case "missingDuration" -> request.remove("durationSeconds");
        case "missingType" -> request.remove("type");
        case "unknownOperation" -> request.put("operation","unknown");
        default -> { }
      }
      jdbc.update("UPDATE media_task SET task_json=? WHERE id=?",change.equals("invalidJson")?"{":json.writeValueAsString(tree),id);
    }
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(0)).andExpect(jsonPath("total.tasks").value(0));
  }

  @Test void includesHistoricalGenerationWithoutNewOperationDiscriminatorWhenNativeExecutionExists() throws Exception {
    for(boolean absent:List.of(true,false)) {
      String id=add("2026-09-11T00:00:00Z","480p","720p",20,"COMPLETE",null,"seedance-2.5");
      var tree=json.readTree(jdbc.queryForObject("SELECT task_json FROM media_task WHERE id=?",String.class,id));
      var request=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data").path("request");
      if(absent)request.remove("operation");else request.putNull("operation");
      jdbc.update("UPDATE media_task SET task_json=? WHERE id=?",json.writeValueAsString(tree),id);
    }
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk()).andExpect(jsonPath("total.tasks").value(2)).andExpect(jsonPath("total.seconds").value(40))
        .andExpect(jsonPath("items[0].enhanced720.tasks").value(2));
  }

  @Test void includesNativeDoubaoBridgeAndKeepsOtherEndpointsExcluded() throws Exception {
    String at="2026-09-11T00:00:00Z";
    for(String base:List.of("http://127.0.0.1:3000/doubao/api/v3","https://provider.example.test/doubao/api/v3/",
        "https://zexapi.com/v1","https://provider.example.test/api/v3","https://provider.example.test/doubao/api/v30",
        "ftp://provider.example.test/doubao/api/v3","https://provider.example.test/doubao/api/v3?x=1")) {
      String id=add(at,"480p","720p",5,"COMPLETE",null,"seedance-2.0");
      jdbc.update("UPDATE media_provider_operation SET plan_json=? WHERE operation_id=?",json.writeValueAsString(Map.of("arkBase",base,"model","doubao-seedance-2-0-260128")),id);
    }
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk()).andExpect(jsonPath("total.tasks").value(2))
        .andExpect(jsonPath("total.seconds").value(10)).andExpect(jsonPath("items[0].enhanced720.tasks").value(2));
  }

  @Test void requiresAdminAndValidInclusiveDateRange() throws Exception {
    mvc.perform(get(PATH).param("from","2026-09-11").param("to","2026-09-11")).andExpect(status().isUnauthorized());
    for(String role:List.of("PROMOTER","API_USER")) mvc.perform(get(PATH).principal(()->"user")
        .with(r->{r.addUserRole(role);return r;}).param("from","2026-09-11").param("to","2026-09-11")).andExpect(status().isForbidden());
    for(String end:List.of("2026-09-10","bad-date","2026-02-30")) mvc.perform(get(PATH).principal(()->"admin")
        .with(r->{r.addUserRole("ADMIN");return r;}).param("from","2026-09-11").param("to",end)).andExpect(status().isBadRequest());
  }

  @Test void fallsBackOnlyWhenRequestedModelIsMissingAndKeepsAllFourProductsSeparate() throws Exception {
    String at="2026-09-11T00:00:00Z";
    for(String model:List.of("seedance-2.0","seedance-2.0-fast","seedance-2.0-mini","seedance-2.5",
        "doubao-seedance-2-5-260628","unknown-model","non-text","route-fallback","actual-fallback")) {
      String id=add(at,"480p","720p",5,"COMPLETE",null,model);
      var tree=json.readTree(jdbc.queryForObject("SELECT task_json FROM media_task WHERE id=?",String.class,id));
      var data=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data");
      var request=(com.fasterxml.jackson.databind.node.ObjectNode)data.path("request");
      if(model.equals("non-text"))request.put("model",123);
      if(model.endsWith("fallback"))request.remove("model");
      if(model.equals("route-fallback"))data.putObject("route").put("requestedModel","seedance-2.0-mini");
      jdbc.update("UPDATE media_task SET task_json=? WHERE id=?",json.writeValueAsString(tree),id);
    }
    mvc.perform(get(PATH).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;})
        .param("from","2026-09-11").param("to","2026-09-11"))
        .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(4))
        .andExpect(jsonPath("total.tasks").value(6)).andExpect(jsonPath("items[2].total.tasks").value(2))
        .andExpect(jsonPath("items[3].total.tasks").value(2));
  }

  String add(String finished,String nativeResolution,String selected,int duration,String phase,String failure,String model) throws Exception {
    String id=UUID.randomUUID().toString();
    var request=Map.of("type","VIDEO","operation","GENERATION","model",model,"resolution",selected,"durationSeconds",duration);
    jdbc.update("INSERT INTO media_task(id,owner_id,idempotency_key,status,task_json,created_at,next_run_at,version,history_deleted) VALUES(?,?,?,'FAILED',?,?,?,0,TRUE)",
        id,"owner",id,json.writeValueAsString(Map.of("data",Map.of("request",request))),Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),0);
    jdbc.update("INSERT INTO media_provider_operation(operation_id,phase,plan_json,generation_finished_at,native_resolution,failure_stage) VALUES(?,?,?,?,?,?)",
        id,phase,json.writeValueAsString(Map.of("arkBase","https://ark.cn-beijing.volces.com/api/v3","model","doubao-seedance-2-5-260628")),Instant.parse(finished).toEpochMilli(),nativeResolution,failure);
    return id;
  }
}
