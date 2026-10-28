package com.xzwl.cloud.media;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.suite.ProductSuites;
import com.xzwl.cloud.media.interfaces.http.ProductSuiteController;
import com.xzwl.cloud.media.interfaces.http.MediaTaskController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductSuiteControllerTest {
  @Test void videoBreakdownRequiresOwnerAndReturnsPrivateStructuredShots() throws Exception {
    var suites=mock(ProductSuites.class);
    when(suites.videoBreakdown(anyString(),any())).thenReturn(new ProductSuites.VideoBreakdown(java.util.List.of(new ProductSuites.VideoShot(0.5,"手持商品",""))));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,mock(MediaTaskController.class))).build();
    String body="{\"productName\":\"商品\",\"sellingPoints\":\"\",\"language\":\"中文\",\"fileIds\":[\"frame\"],\"timestamps\":[0.5],\"durationSeconds\":4}";
    mvc.perform(post("/api/v1/media/product-suites/video-breakdown").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    verifyNoInteractions(suites);
    mvc.perform(post("/api/v1/media/product-suites/video-breakdown").principal(()->"owner").contentType("application/json").content(body))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
      .andExpect(jsonPath("shots[0].visual").value("手持商品")).andExpect(jsonPath("shots[0].narration").value(""));
  }
  @Test void planRequiresAnOwnerBeforeCallingTheProvider() throws Exception {
    var suites=mock(ProductSuites.class);
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,mock(MediaTaskController.class))).build();
    mvc.perform(post("/api/v1/media/product-suites/plan").contentType("application/json")
      .content("{\"productName\":\"商品\",\"sellingPoints\":\"\",\"language\":\"中文\",\"fileIds\":[\"file\"]}"))
      .andExpect(status().isUnauthorized());
    verifyNoInteractions(suites);
  }

  @Test void planReturnsTheProviderSelection() throws Exception {
    var suites=mock(ProductSuites.class);
    when(suites.plan(anyString(),any())).thenReturn(new ProductSuites.Plan(java.util.List.of(
        new ProductSuites.ModuleCount(ProductSuites.Module.WHITE_BACKGROUND,2))));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,mock(MediaTaskController.class))).build();
    String body="{\"productName\":\"商品\",\"sellingPoints\":\"\",\"language\":\"日文\",\"fileIds\":[\"file\"]}";
    mvc.perform(post("/api/v1/media/product-suites/plan").principal(()->"user").contentType("application/json").content(body))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
      .andExpect(jsonPath("modules[0].module").value("WHITE_BACKGROUND")).andExpect(jsonPath("modules[0].count").value(2));
    mvc.perform(post("/api/v1/media/product-suites/plan").principal(()->"user").contentType("application/json").content(body))
      .andExpect(status().isOk());
    verify(suites,times(2)).plan(eq("user"),eq(new ProductSuites.CopyRequest("商品","","日文",java.util.List.of("file"))));
  }
  @Test void quotesBindOptionalToolConfigurationAndRejectUnsupportedOptionValues() throws Exception {
    var suites=mock(ProductSuites.class);
    when(suites.quote(anyString(),any())).thenReturn(new ProductSuites.Quote("24.6912",java.util.List.of()));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,mock(MediaTaskController.class))).build();
    String body="""
      {"templateId":"general","templateVersion":1,"productName":"商品","sellingPoints":"","language":"韩文",
       "fileIds":["file"],"modules":["WHITE_BACKGROUND"],"model":"model","aspectRatio":"3:4","resolution":"1K",
       "options":{"platform":"亚马逊","moduleCounts":[{"module":"WHITE_BACKGROUND","count":2}]}}
      """;
    mvc.perform(post("/api/v1/media/product-suites/quotes").principal(()->"user").contentType("application/json").content(body))
      .andExpect(status().isOk()).andExpect(jsonPath("points").value("24.6912"));
    var capture=org.mockito.ArgumentCaptor.forClass(ProductSuites.Request.class);
    verify(suites).quote(eq("user"),capture.capture());
    org.junit.jupiter.api.Assertions.assertEquals(2,capture.getValue().count(ProductSuites.Module.WHITE_BACKGROUND));
    org.junit.jupiter.api.Assertions.assertEquals("亚马逊",capture.getValue().options().platform());
    mvc.perform(post("/api/v1/media/product-suites/quotes").principal(()->"user").contentType("application/json").content(body.replace("亚马逊","invalid")))
      .andExpect(status().isBadRequest());
  }
  @Test void requiresPrincipalBeforeEveryOperationAndDoesNotCachePrivateCatalog() throws Exception {
    var suites=mock(ProductSuites.class);var tasks=mock(MediaTaskController.class);
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,tasks)).build();
    mvc.perform(get("/api/v1/media/product-suites/templates")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/media/product-suites")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/media/product-suites/example")).andExpect(status().isUnauthorized());
    verifyNoInteractions(suites);
    when(suites.templates()).thenReturn(java.util.List.of(ProductSuites.STREETWEAR));
    mvc.perform(get("/api/v1/media/product-suites/templates").principal(()->"user"))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
      .andExpect(jsonPath("templates[0].modules.length()").value(6)).andExpect(jsonPath("copyAvailable").value(false));
  }
  @Test void projectsCurrentProgressAndKeepsMostRecentSuccessfulResult() throws Exception {
    var suites=mock(ProductSuites.class);var tasks=mock(MediaTaskController.class);
    var request=new ProductSuites.Request("streetwear",1,"套装","","中文",java.util.List.of("file"),
      java.util.List.of(ProductSuites.Module.DETAILS,ProductSuites.Module.OVERVIEW),"model","3:4","1K");
    var suite=new ProductSuites.Suite("suite",java.time.Instant.EPOCH,request,java.util.List.of(
      new ProductSuites.Item(ProductSuites.Module.DETAILS,java.util.List.of("old-success","failed-retry","latest")),
      new ProductSuites.Item(ProductSuites.Module.OVERVIEW,java.util.List.of("overview"))));
    when(suites.get("user","suite")).thenReturn(suite);
    when(tasks.get(any(),eq("old-success"))).thenReturn(task("old-success","SUCCEEDED",true));
    when(tasks.get(any(),eq("failed-retry"))).thenReturn(task("failed-retry","FAILED",false));
    when(tasks.get(any(),eq("latest"))).thenReturn(task("latest","RUNNING",false),task("latest","FAILED",false));
    when(tasks.get(any(),eq("overview"))).thenReturn(task("overview","SUCCEEDED",true));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,tasks)).build();
    mvc.perform(get("/api/v1/media/product-suites/suite").principal(()->"user"))
      .andExpect(status().isOk()).andExpect(jsonPath("status").value("RUNNING"))
      .andExpect(jsonPath("items[0].task.id").value("latest"))
      .andExpect(jsonPath("items[0].previousResult.id").value("old-success"));
    mvc.perform(get("/api/v1/media/product-suites/suite").principal(()->"user"))
      .andExpect(jsonPath("status").value("PARTIAL_SUCCESS"));
  }
  @Test void partialChildOutputKeepsTheSuitePartialUntilAllChildrenFullySucceed() throws Exception {
    var suites=mock(ProductSuites.class);var tasks=mock(MediaTaskController.class);
    var request=new ProductSuites.Request("general",1,"商品","","中文",java.util.List.of("file"),
        java.util.List.of(ProductSuites.Module.DETAILS,ProductSuites.Module.OVERVIEW),"model","3:4","1K",
        new ProductSuites.Options(null,null,null,null,null,null,java.util.List.of(new ProductSuites.ModuleCount(ProductSuites.Module.DETAILS,3))));
    when(suites.get("user","suite")).thenReturn(new ProductSuites.Suite("suite",java.time.Instant.EPOCH,request,java.util.List.of(
        new ProductSuites.Item(ProductSuites.Module.DETAILS,java.util.List.of("details")),
        new ProductSuites.Item(ProductSuites.Module.OVERVIEW,java.util.List.of("overview")))));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,tasks)).build();
    for(var scenario:new String[][]{{"PARTIAL_SUCCESS","FAILED","PARTIAL_SUCCESS"},{"PARTIAL_SUCCESS","SUCCEEDED","PARTIAL_SUCCESS"},
        {"PARTIAL_SUCCESS","PARTIAL_SUCCESS","PARTIAL_SUCCESS"},{"PARTIAL_SUCCESS","RUNNING","RUNNING"},
        {"SUCCEEDED","SUCCEEDED","SUCCEEDED"},{"FAILED","FAILED","FAILED"}}) {
      when(tasks.get(any(),eq("details"))).thenReturn(task("details",scenario[0],scenario[0].contains("SUCCESS") || scenario[0].equals("SUCCEEDED"),3));
      when(tasks.get(any(),eq("overview"))).thenReturn(task("overview",scenario[1],scenario[1].contains("SUCCESS") || scenario[1].equals("SUCCEEDED")));
      mvc.perform(get("/api/v1/media/product-suites/suite").principal(()->"user"))
        .andExpect(status().isOk()).andExpect(jsonPath("status").value(scenario[2]))
        .andExpect(jsonPath("items[0].task.count").value(3))
        .andExpect(jsonPath("items[0].task.results.length()").value(scenario[0].equals("SUCCEEDED")?3:scenario[0].equals("PARTIAL_SUCCESS")?1:0));
    }
  }
  private MediaTaskController.TaskResponse task(String id,String status,boolean result) { return task(id,status,result,1); }
  private MediaTaskController.TaskResponse task(String id,String status,boolean result,int count) {
    return new MediaTaskController.TaskResponse(id,status,"IMAGE","model","3:4","1K",count,0,
      java.time.Instant.EPOCH,java.time.Instant.EPOCH.plusSeconds(60),
      result?java.util.stream.IntStream.range(0,status.equals("SUCCEEDED")?count:1).mapToObj(i->new MediaTaskController.OutputResponse(i,"result-file-"+i)).toList():java.util.List.of(),
      "prompt",java.util.List.of(),"model",false,null,java.util.List.of(),null,
      com.xzwl.cloud.media.domain.task.ReferenceMode.FIRST_LAST_FRAME,com.xzwl.cloud.media.domain.task.TaskOperation.GENERATION,java.util.List.of());
  }
  @Test void resultNamesFollowTheStoredTemplateInsteadOfLegacyModuleTitles() throws Exception {
    var suites=mock(ProductSuites.class);var tasks=mock(MediaTaskController.class);
    var request=new ProductSuites.Request("general",1,"猫砂除臭剂","","中文",java.util.List.of("file"),
      java.util.List.of(ProductSuites.Module.MODEL_SCENE),"model","3:4","1K");
    when(suites.get("user","suite")).thenReturn(new ProductSuites.Suite("suite",java.time.Instant.EPOCH,request,
      java.util.List.of(new ProductSuites.Item(ProductSuites.Module.MODEL_SCENE,java.util.List.of("scene")))));
    when(tasks.get(any(),eq("scene"))).thenReturn(task("scene","SUCCEEDED",true));
    var mvc=MockMvcBuilders.standaloneSetup(new ProductSuiteController(suites,tasks)).build();
    mvc.perform(get("/api/v1/media/product-suites/suite").principal(()->"user"))
      .andExpect(status().isOk()).andExpect(jsonPath("items[0].name").value("使用场景图"));
  }
}
