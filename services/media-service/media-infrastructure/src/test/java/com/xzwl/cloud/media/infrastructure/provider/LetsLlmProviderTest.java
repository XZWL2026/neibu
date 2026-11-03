package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.TaskRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LetsLlmProviderTest {
  private final ObjectMapper json=new ObjectMapper();
  private static final String BASE="https://api.letsllm.com/api/v3";


  @ParameterizedTest @EnumSource(com.xzwl.cloud.media.domain.task.MediaReference.Kind.class)
  void usesNewChannelAssetIdsForEveryReferenceKind(com.xzwl.cloud.media.domain.task.MediaReference.Kind kind) throws Exception {
    var http=mock(ProviderHttp.class);
    when(http.encode(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
    when(http.call(eq("POST"),eq(BASE+"/contents/generations/tasks"),anyMap(),anyString(),nullable(Integer.class),nullable(Integer.class)))
        .thenReturn(json.readTree("{\"id\":\"cgt-asset\"}"));
    var reference=new com.xzwl.cloud.media.domain.task.MediaReference(null,"https://cdn.example/reference",kind,kind==com.xzwl.cloud.media.domain.task.MediaReference.Kind.IMAGE?null:3.0);
    var request=new TaskRequest(MediaType.VIDEO,"seedance-2.5","reference test","16:9","480p",1,5,
        java.util.List.of(reference),com.xzwl.cloud.media.domain.task.ReferenceMode.OMNI);
    var plan=new ProviderSettings.Plan(BASE,"doubao-seedance-2-5-260628",MediaType.VIDEO,"480p","480p","16:9",null,null,null,null,0);
    assertEquals("cgt-asset",new ArkMediaAdapter(http).submitVideo(request,plan,"fixture-key",Map.of(0,"new-channel-asset"),"default"));
    var body=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(http).call(eq("POST"),eq(BASE+"/contents/generations/tasks"),anyMap(),body.capture(),nullable(Integer.class),nullable(Integer.class));
    var item=json.readTree(body.getValue()).path("content").get(1);String type=kind.name().toLowerCase(java.util.Locale.ROOT);
    assertEquals("asset://new-channel-asset",item.path(type+"_url").path("url").asText());
    assertEquals("reference_"+type,item.path("role").asText());
  }

  @Test void mapsOnlyReviewedAliasesAndOnlyForTheLetsLlmHost() {
    for(var entry:Map.of(
        "doubao-seedance-2-5-260628","doubao-seedance-2-5",
        "doubao-seedance-2-0-260128","doubao-seedance-2-0",
        "doubao-seedance-2-0-fast-260128","doubao-seedance-2-0-fast",
        "doubao-seedance-2-0-mini-260615","doubao-seedance-2-0-mini").entrySet()) {
      assertEquals(entry.getValue(),LetsLlmModels.requestModel(BASE,entry.getKey()));
      assertEquals(entry.getKey(),LetsLlmModels.canonical(entry.getValue()));
      assertEquals(entry.getKey(),LetsLlmModels.requestModel("https://ark.cn-beijing.volces.com/api/v3",entry.getKey()));
    }
    assertFalse(LetsLlmModels.matches("https://api.letsllm.com.example/api/v3"));
    assertEquals("doubao-seedance-future",LetsLlmModels.canonical("doubao-seedance-future"));
  }

  @Test void readsOpenAiCatalogButKeepsVersionedRoutingAndReferenceCapabilities() throws Exception {
    var http=mock(ProviderHttp.class);
    var store=mock(ArkConnectionStore.class);
    when(store.load("letsllm")).thenReturn(new ArkConnectionStore.Connection(1,"letsllm",BASE,"fixture-secret"));
    when(http.call(eq("GET"),eq("https://api.letsllm.com/v1/models"),anyMap(),isNull())).thenReturn(json.readTree("""
        {"data":[{"id":"doubao-seedance-2-5"},{"id":"doubao-seedance-2-5-260628"},
        {"id":"doubao-seedance-2-0-fast"},{"id":"unknown-video"},{"id":"text-model"}]}
        """));
    var result=new HttpArkModelCatalog(store,http).list("letsllm");
    assertTrue(result.success());assertEquals(4,result.models().size());
    var video=result.models().stream().filter(m->m.id().equals("doubao-seedance-2-5-260628")).findFirst().orElseThrow();
    assertEquals("VIDEO",video.type());assertEquals(30,video.capability().maxDurationSeconds());
    assertEquals("UNSUPPORTED",result.models().stream().filter(m->m.id().equals("unknown-video")).findFirst().orElseThrow().type());
    verify(http).call("GET","https://api.letsllm.com/v1/models",Map.of("Authorization","Bearer fixture-secret"),null);
  }

  @Test void submitsArkContentWithAliasAndPollsTheSameReceipt() throws Exception {
    var http=mock(ProviderHttp.class);
    when(http.encode(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
    when(http.call(eq("POST"),eq(BASE+"/contents/generations/tasks"),anyMap(),anyString(),nullable(Integer.class),nullable(Integer.class)))
        .thenReturn(json.readTree("{\"id\":\"cgt-fixture\"}"));
    var request=json.readValue("""
        {"type":"VIDEO","model":"seedance-2.5","prompt":"camera moves slowly","aspectRatio":"16:9",
        "resolution":"720p","count":1,"durationSeconds":5,"references":[{"httpsUrl":"https://cdn.example/frame.png"}]}
        """,TaskRequest.class);
    var plan=new ProviderSettings.Plan(BASE,"doubao-seedance-2-5-260628",MediaType.VIDEO,"480p","720p","16:9",null,null,null,null,0);
    var adapter=new ArkMediaAdapter(http);
    assertEquals("cgt-fixture",adapter.submitVideo(request,plan,"fixture-secret"));
    var body=org.mockito.ArgumentCaptor.forClass(String.class);
    verify(http).call(eq("POST"),eq(BASE+"/contents/generations/tasks"),anyMap(),body.capture(),nullable(Integer.class),nullable(Integer.class));
    var payload=json.readTree(body.getValue());
    assertEquals("doubao-seedance-2-5",payload.path("model").asText());
    assertEquals("adaptive",payload.path("ratio").asText());
    assertEquals("first_frame",payload.path("content").get(1).path("role").asText());
    when(http.call(eq("GET"),eq(BASE+"/contents/generations/tasks/cgt-fixture"),anyMap(),isNull(),nullable(Integer.class),nullable(Integer.class)))
        .thenReturn(json.readTree("{\"id\":\"cgt-fixture\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://cdn.example/result.mp4\"}}"));
    assertEquals(com.xzwl.cloud.media.application.task.ProviderPort.ProviderPoll.Status.COMPLETED,adapter.poll("cgt-fixture",plan,"fixture-secret").status());
  }
}
