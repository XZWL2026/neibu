package com.xzwl.cloud.media.infrastructure.suite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.catalog.GenerationCatalogService;
import com.xzwl.cloud.media.application.suite.ProductSuites;
import com.xzwl.cloud.media.application.suite.ProductSuites.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.infrastructure.task.MyBatisMediaTaskStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(classes=JdbcProductSuitesTest.Config.class,properties={
  "spring.datasource.url=jdbc:h2:mem:suites;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa","spring.datasource.password=",
  "spring.flyway.locations=classpath:db/media-migration/common"})
class JdbcProductSuitesTest {
  @Configuration @EnableAutoConfiguration @MapperScan("com.xzwl.cloud.media.infrastructure.task")
  @Import(MyBatisMediaTaskStore.class) static class Config {}
  @Autowired JdbcTemplate jdbc;
  @Autowired MyBatisMediaTaskStore store;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactions;
  final Clock clock=Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"),ZoneOffset.UTC);
  String owner;
  GenerationCatalogService catalog;
  StoragePort storage;
  MediaTaskService tasks;
  JdbcProductSuites suites;
  @BeforeEach void setup() {
    jdbc.update("DELETE FROM media_product_suite_item");
    jdbc.update("DELETE FROM media_product_suite");
    owner=UUID.randomUUID().toString();
    catalog=mock(GenerationCatalogService.class);storage=mock(StoragePort.class);
    when(catalog.options(anyString())).thenReturn(List.of(new GenerationCatalogService.Option(MediaType.IMAGE,"image-model","参考生图",
      List.of("1K"),List.of("3:4"),4,0,0,0,8,false,null)));
    when(catalog.quote(anyString(),any())).thenReturn(new BigDecimal("12.3456"));
    when(storage.resolveReferences(anyString(),anyList())).thenReturn(List.of(new MediaReference(null,"https://files.example/product.jpg")));
    tasks=new MediaTaskService(store,clock,new TaskPolicy(Duration.ofMinutes(10),Duration.ofMinutes(10),Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofMinutes(1),10));
    suites=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock);
  }
  Request request(String name) {
    return new Request("streetwear",1,name,"红格纹上衣与蓝色牛仔裤","中文",List.of("product-file"),
      List.of(ProductSuites.Module.WHITE_BACKGROUND,ProductSuites.Module.SELLING_POINTS,ProductSuites.Module.FEATURES,ProductSuites.Module.DETAILS,ProductSuites.Module.MODEL_SCENE,ProductSuites.Module.OVERVIEW),"image-model","3:4","1K");
  }
  @Test void currentCatalogUpgradesOnlyDetailPageAndKeepsLegacyModulesAndVersionReadable() {
    var detail=suites.templates().stream().filter(t->t.id().equals("detail-page")).findFirst().orElseThrow();
    assertEquals(2,detail.version());
    assertEquals(List.of("WHITE_BACKGROUND","SELLING_POINTS","MODEL_SCENE","MULTI_ANGLE","BRAND_STORY","COMPARISON","CRAFT","SIZE","SPECS","ACCESSORIES","SERIES","INGREDIENTS","AFTER_SALES","USAGE_ADVICE"),
        detail.modules().stream().map(m->m.id().name()).toList());
    var apparel=suites.templates().stream().filter(t->t.id().equals("apparel-set")).findFirst().orElseThrow();
    assertEquals(List.of("WHITE_BACKGROUND","VOLUME_3D","MANNEQUIN","DETAILS","SELLING_POINTS"),apparel.modules().stream().map(m->m.id().name()).toList());
    for(var template:List.of(ProductSuites.GENERAL,ProductSuites.STREETWEAR))
      assertEquals(List.of("WHITE_BACKGROUND","SELLING_POINTS","FEATURES","DETAILS","MODEL_SCENE","OVERVIEW"),template.modules().stream().map(m->m.id().name()).toList());
    var legacy=new Request("detail-page",1,"商品","","中文",List.of("file"),
        List.of(ProductSuites.Module.FEATURES,ProductSuites.Module.DETAILS,ProductSuites.Module.OVERVIEW),"image-model","3:4","1K");
    assertEquals(ProductSuites.DETAIL_PAGE,ProductSuites.template(legacy));
    assertEquals(legacy,suites.get(owner,suites.submit(owner,"old-detail",legacy).id()).request());
  }
  @Test void catalogGeneratesCategorySpecificTasksAndQuotesOnlyItsModules() {
    var templates=suites.templates().subList(0,4);
    assertEquals(List.of("general","apparel","beauty","streetwear"),templates.stream().map(Template::id).toList());
    assertEquals(List.of("通用商品套图","服装套图","美妆护肤套图","复古服装套图"),templates.stream().map(Template::name).toList());
    for(var template:templates) {
      var selected=template.modules().stream().map(ModuleOption::id).toList();
      int count=template.id().equals("apparel")?5:6;
      assertEquals(count,selected.size());
      var request=new Request(template.id(),template.version(),"测试商品","已确认卖点","中文",List.of("product-file"),selected,"image-model","3:4","1K");
      assertEquals(new BigDecimal("12.3456").multiply(BigDecimal.valueOf(count)).toPlainString(),suites.quote(owner,request).points());
      var suite=suites.submit(owner,template.id(),request);
      assertEquals(count,suite.items().size());
      for(var item:suite.items()) {
        var module=template.modules().stream().filter(m->m.id()==item.module()).findFirst().orElseThrow();
        String prompt=tasks.get(owner,item.taskIds().getFirst()).data().request().prompt();
        assertTrue(prompt.contains(template.style()));
        assertTrue(prompt.contains("当前模块："+module.name()+"。"+module.description()));
        assertTrue(prompt.contains("不得补造"));
        if(template.id().equals("general") || template.id().equals("beauty")) {
          assertFalse(prompt.contains("穿戴同一套服装"));
          assertFalse(prompt.contains("酒红、牛仔蓝"));
        }
        if(template.id().equals("general") && item.module()==ProductSuites.Module.MODEL_SCENE) assertTrue(prompt.contains("使用场景图"));
        if(template.id().equals("beauty") && item.module()==ProductSuites.Module.FEATURES) {
          assertTrue(prompt.contains("质地展示"));
          assertTrue(prompt.contains("未提供"));
        }
      }
      assertEquals(suite,new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock).get(owner,suite.id()));
    }
  }
  @Test void imageToolsQuotePersistAndRegenerateWithTheirOriginalReferenceRoles() {
    assertEquals(List.of("general","apparel","beauty","streetwear","detail-page","image-replica","product-retouch","model-wear","apparel-set"),
        suites.templates().stream().map(Template::id).toList());
    var expectedModules=Map.of(
        "detail-page",List.of(ProductSuites.Module.WHITE_BACKGROUND,ProductSuites.Module.SELLING_POINTS,ProductSuites.Module.FEATURES,
            ProductSuites.Module.DETAILS,ProductSuites.Module.MODEL_SCENE,ProductSuites.Module.OVERVIEW),
        "image-replica",List.of(ProductSuites.Module.OVERVIEW),
        "product-retouch",List.of(ProductSuites.Module.DETAILS),
        "model-wear",List.of(ProductSuites.Module.MODEL_SCENE));
    for(var template:List.of(ProductSuites.DETAIL_PAGE,ProductSuites.IMAGE_REPLICA,ProductSuites.PRODUCT_RETOUCH,ProductSuites.MODEL_WEAR)) {
      var modules=template.modules().stream().map(ModuleOption::id).toList();
      assertEquals(expectedModules.get(template.id()),modules);
      var request=new Request(template.id(),1,"商品","已确认资料","中文",List.of("product-file","reference-file"),
          modules,"image-model","3:4","1K");
      var quote=suites.quote(owner,request);
      assertEquals(new BigDecimal("12.3456").multiply(BigDecimal.valueOf(modules.size())).toPlainString(),quote.points());
      assertEquals(modules,quote.modules().stream().map(ModuleQuote::module).toList());
      assertTrue(quote.modules().stream().allMatch(q->q.points().equals("12.3456")));
      for(var module:modules) verify(catalog).quote(owner,request.task(module,template));
      var suite=suites.submit(owner,template.id(),request);
      assertEquals(request,suite.request());
      assertEquals(modules,suite.items().stream().map(Item::module).toList());
      for(var item:suite.items()) {
        var taskRequest=tasks.get(owner,item.taskIds().getFirst()).data().request();
        assertEquals(request.references(),taskRequest.references());
        assertEquals(request.task(item.module(),template),taskRequest);
        String prompt=taskRequest.prompt();
        assertTrue(prompt.contains(template.style()));
        assertTrue(prompt.contains(template.module(item.module()).description()));
        if(template.id().equals("image-replica") || template.id().equals("model-wear")) {
          assertFalse(prompt.contains("其余为补充细节"));
          assertTrue(prompt.contains("第一张参考图"));
          assertTrue(prompt.contains("第二张参考图"));
          assertTrue(prompt.contains("不得混入"));
          assertTrue(prompt.contains("Logo"));
          assertTrue(prompt.contains(template.id().equals("image-replica")?"效果参考":"成年模特参考"));
        }
        if(template.id().equals("detail-page")) {
          assertTrue(prompt.contains("拼接"));
          assertTrue(prompt.contains("不添加边框"));
        }
        if(template.id().equals("product-retouch")) {
          assertTrue(prompt.contains("光影"));
          assertTrue(prompt.contains("纹理"));
          assertTrue(prompt.contains("不臆造"));
        }
      }
      var restarted=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock);
      assertEquals(suite,restarted.get(owner,suite.id()));
      assertEquals(suite,restarted.submit(owner,template.id(),request));
      var item=suite.items().getFirst();String original=item.taskIds().getFirst();
      var source=tasks.get(owner,original);var lease=store.claim(original,"test",clock.instant(),clock.instant().plusSeconds(30)).orElseThrow();
      store.save(lease,source.advance(TaskStatus.FAILED,source.data(),clock.instant()),List.of(),clock.instant());
      var updated=restarted.regenerate(owner,suite.id(),item.module(),original,"retry");
      var attempts=updated.items().getFirst().taskIds();
      assertEquals(2,attempts.size());
      assertEquals(source.data().request(),tasks.get(owner,attempts.getLast()).data().request());
      assertEquals(updated,restarted.regenerate(owner,suite.id(),item.module(),original,"retry"));
    }
  }
  @Test void twoRoleToolsRejectMissingExtraOrDuplicateImagesBeforePricingOrPersisting() {
    for(String id:List.of("image-replica","model-wear")) {
      var module=id.equals("image-replica")?ProductSuites.Module.OVERVIEW:ProductSuites.Module.MODEL_SCENE;
      var valid=new Request(id,1,"商品","","中文",List.of("product-file","reference-file"),List.of(module),"image-model","3:4","1K");
      assertEquals(id,ProductSuites.template(valid).id());
      for(var files:List.of(List.of("product-file"),List.of("product-file","reference-file","extra-file","fourth-file","fifth-file"))) {
        var invalid=new Request(id,1,"商品","","中文",files,List.of(module),"image-model","3:4","1K");
        assertThrows(IllegalArgumentException.class,()->ProductSuites.template(invalid));
        assertThrows(IllegalArgumentException.class,()->suites.quote(owner,invalid));
        assertThrows(IllegalArgumentException.class,()->suites.submit(owner,"invalid",invalid));
      }
      assertThrows(IllegalArgumentException.class,()->new Request(id,1,"商品","","中文",List.of("same-file","same-file"),
          List.of(module),"image-model","3:4","1K"));
    }
    verify(catalog,never()).quote(anyString(),any());
    verifyNoInteractions(storage);
    assertTrue(suites.list(owner,20,null).isEmpty());
    assertTrue(tasks.list(owner,100,null,false).isEmpty());
  }
  @Test void retouchAcceptsOneThroughEightImagesAndPreservesMainThenDetailOrder() {
    for(int count:List.of(1,8)) {
      var files=java.util.stream.IntStream.range(0,count).mapToObj(i->"file-"+i).toList();
      var request=new Request("product-retouch",1,"商品","","中文",files,List.of(ProductSuites.Module.DETAILS),"image-model","3:4","1K");
      assertEquals("12.3456",suites.quote(owner,request).points());
      var suite=suites.submit(owner,"retouch-"+count,request);
      var task=tasks.get(owner,suite.items().getFirst().taskIds().getFirst()).data().request();
      assertEquals(files,task.references().stream().map(MediaReference::fileId).toList());
      assertTrue(task.prompt().contains("第一张参考图是商品主图，其余为补充细节"));
    }
    for(var files:List.of(List.<String>of(),java.util.stream.IntStream.range(0,9).mapToObj(i->"file-"+i).toList())) {
      assertThrows(IllegalArgumentException.class,()->new Request("product-retouch",1,"商品","","中文",files,
          List.of(ProductSuites.Module.DETAILS),"image-model","3:4","1K"));
    }
  }
  @Test void oldJsonAndConstructorsStayCompatibleWhileOnlyCountDifferencesAreStored() throws Exception {
    var old=request("旧商品");
    var body=json.valueToTree(old);((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("options");
    assertEquals(old,json.treeToValue(body,Request.class));
    assertNull(old.options());
    var explicit=new Request(old.templateId(),old.templateVersion(),old.productName(),old.sellingPoints(),old.language(),old.fileIds(),old.modules(),old.model(),old.aspectRatio(),old.resolution(),
        new Options(null,null,List.of(),null,null,null,List.of(new ModuleCount(ProductSuites.Module.DETAILS,1))));
    assertEquals(old,explicit);
    var suite=suites.submit(owner,"legacy-json",old);
    jdbc.update("UPDATE media_product_suite SET request_json=? WHERE id=?",json.writeValueAsString(body),suite.id());
    assertEquals(suite,suites.get(owner,suite.id()));
    assertEquals(suite,suites.submit(owner,"legacy-json",explicit));
  }
  @Test void moduleCountsAffectQuotesPersistedTasksReplayAndRegeneration() throws Exception {
    when(catalog.quote(anyString(),any())).thenAnswer(call->new BigDecimal("12.3456").multiply(BigDecimal.valueOf(((TaskRequest)call.getArgument(1)).count())));
    var selected=List.of(ProductSuites.Module.WHITE_BACKGROUND,ProductSuites.Module.SELLING_POINTS);
    var request=toolRequest("general",selected,new Options("亚马逊",null,null,null,null,null,
        List.of(new ModuleCount(selected.get(1),3),new ModuleCount(selected.getFirst(),1))),List.of("file"));
    assertEquals(List.of(new ModuleCount(selected.get(1),3)),request.options().moduleCounts());
    assertEquals(request,json.readValue(json.writeValueAsString(request),Request.class));
    assertEquals("49.3824",suites.quote(owner,request).points());
    var suite=suites.submit(owner,"counts",request);
    assertEquals(List.of(1,3),suite.items().stream().map(i->tasks.get(owner,i.taskIds().getFirst()).data().request().count()).toList());
    var restarted=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock);
    assertEquals(suite,restarted.get(owner,suite.id()));
    assertEquals(suite,restarted.submit(owner,"counts",request));
    var item=suite.items().get(1);var source=tasks.get(owner,item.taskIds().getFirst());
    var lease=store.claim(source.id(),"test",clock.instant(),clock.instant().plusSeconds(30)).orElseThrow();
    store.save(lease,source.advance(TaskStatus.FAILED,source.data(),clock.instant()),List.of(),clock.instant());
    var updated=restarted.regenerate(owner,suite.id(),item.module(),source.id(),"retry");
    assertEquals(source.data().request(),tasks.get(owner,updated.items().get(1).taskIds().getLast()).data().request());
    assertEquals(updated,restarted.regenerate(owner,suite.id(),item.module(),source.id(),"retry"));
    assertThrows(TaskConflictException.class,()->restarted.submit(owner,"counts",toolRequest("general",selected,null,List.of("file"))));
  }
  @Test void validatesEveryModuleCountBeforeCreatingAnyTask() {
    doAnswer(call->{if(((TaskRequest)call.getArgument(1)).count()>1) throw new DefinitiveRejectionException("UNSUPPORTED_GENERATION_SELECTION");return null;})
        .when(catalog).validate(anyString(),any());
    var request=toolRequest("general",List.of(ProductSuites.Module.WHITE_BACKGROUND,ProductSuites.Module.DETAILS),
        new Options(null,null,null,null,null,null,List.of(new ModuleCount(ProductSuites.Module.DETAILS,2))),List.of("file"));
    assertThrows(DefinitiveRejectionException.class,()->suites.submit(owner,"unsupported-count",request));
    assertThrows(DefinitiveRejectionException.class,()->suites.quote(owner,request));
    assertTrue(suites.list(owner,20,null).isEmpty());assertTrue(tasks.list(owner,100,null,false).isEmpty());
    verifyNoInteractions(storage);verify(catalog,never()).quote(anyString(),any());
  }
  @Test void validatesOptionsValuesApplicabilityCountsAndLanguage() {
    for(String platform:ProductSuites.PLATFORMS) {
      var options=new Options(platform,null,null,null,null,null,null);
      assertNotNull(toolRequest("general",List.of(ProductSuites.Module.WHITE_BACKGROUND),options,List.of("file")));
      assertNotNull(toolRequest("image-replica",List.of(ProductSuites.Module.OVERVIEW),options,List.of("file","reference")));
    }
    for(int count:new int[]{0,5}) assertThrows(IllegalArgumentException.class,()->new ModuleCount(ProductSuites.Module.DETAILS,count));
    assertThrows(IllegalArgumentException.class,()->new ModuleCount(null,1));
    assertThrows(IllegalArgumentException.class,()->new Options("ignore rules",null,null,null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->new Options(null,"other",null,null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->new Options(null,null,List.of("OTHER"),null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->new Options(null,null,List.of("GLOSS","GLOSS"),null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->new Options(null,null,null,"SIDE",null,null,null));
    assertThrows(IllegalArgumentException.class,()->new Options(null,null,null,null,null,null,List.of(new ModuleCount(ProductSuites.Module.DETAILS,1),new ModuleCount(ProductSuites.Module.DETAILS,2))));
    for(var invalid:List.of(new Options(null,"STYLE",null,null,null,null,null),new Options(null,null,List.of("GLOSS"),null,null,null,null),
        new Options(null,null,null,"FRONT",null,null,null),new Options(null,null,null,null,false,null,null),new Options(null,null,null,null,null,true,null),
        new Options(null,null,null,null,null,null,List.of(new ModuleCount(ProductSuites.Module.DETAILS,1)))))
      assertThrows(IllegalArgumentException.class,()->toolRequest("general",List.of(ProductSuites.Module.WHITE_BACKGROUND),invalid,List.of("file")));
    assertThrows(IllegalArgumentException.class,()->toolRequest("product-retouch",List.of(ProductSuites.Module.DETAILS),new Options("亚马逊",null,null,null,null,null,null),List.of("file")));
    assertThrows(IllegalArgumentException.class,()->toolRequest("image-replica",List.of(ProductSuites.Module.OVERVIEW),new Options(null,null,null,null,null,null,List.of(new ModuleCount(ProductSuites.Module.OVERVIEW,2))),List.of("file","reference")));
    for(String language:ProductSuites.LANGUAGES) {
      var r=new Request("general",1,"商品","",""+language,List.of("file"),List.of(ProductSuites.Module.WHITE_BACKGROUND),"image-model","3:4","1K");
      assertTrue(r.task(ProductSuites.Module.WHITE_BACKGROUND,ProductSuites.GENERAL).prompt().contains("输出文字语言："+language));
      assertNotNull(new CopyRequest("商品","",language,List.of("file")));
    }
  }
  @Test void toolOptionsBecomeSystemDirectionsAndReferenceRolesSupportTwoThroughFourImages() {
    for(String mode:List.of("STYLE","HIGH")) {
      var r=toolRequest("image-replica",List.of(ProductSuites.Module.OVERVIEW),new Options("eBay",mode,null,null,null,null,null),List.of("product","reference"));
      String prompt=r.task(ProductSuites.Module.OVERVIEW,ProductSuites.IMAGE_REPLICA).prompt();
      assertTrue(prompt.indexOf("上架平台：eBay")<prompt.indexOf("商品资料开始"));
      assertTrue(prompt.contains(mode.equals("HIGH")?"高度复刻":"参考风格"));
    }
    var retouch=toolRequest("product-retouch",List.of(ProductSuites.Module.DETAILS),
        new Options(null,null,List.of("GLOSS","REPAIR","SHARPNESS","COLOR","PERSPECTIVE"),null,null,null,null),List.of("file"));
    String prompt=retouch.task(ProductSuites.Module.DETAILS,ProductSuites.PRODUCT_RETOUCH).prompt();
    for(String title:List.of("光泽增强","瑕疵修复","清晰度增强","色彩校正","透视校正")) assertTrue(prompt.indexOf(title)<prompt.indexOf("商品资料开始"));
    var apparel=toolRequest("apparel-set",List.of(ProductSuites.Module.VOLUME_3D,ProductSuites.Module.MANNEQUIN),
        new Options(null,null,null,"BACK",true,false,null),List.of("file"));
    assertTrue(apparel.task(ProductSuites.Module.VOLUME_3D,ProductSuites.APPAREL_SET).prompt().contains("当前模块背景：纯白"));
    assertTrue(apparel.task(ProductSuites.Module.MANNEQUIN,ProductSuites.APPAREL_SET).prompt().contains("当前模块背景：按整套"));
    assertTrue(apparel.task(ProductSuites.Module.VOLUME_3D,ProductSuites.APPAREL_SET).prompt().contains("未提供背面"));
    for(String id:List.of("image-replica","model-wear")) for(int count=2;count<=4;count++) {
      var module=id.equals("image-replica")?ProductSuites.Module.OVERVIEW:ProductSuites.Module.MODEL_SCENE;
      var files=java.util.stream.IntStream.range(0,count).mapToObj(i->"file-"+i).toList();
      var request=toolRequest(id,List.of(module),null,files);var template=ProductSuites.template(request);
      var suite=suites.submit(owner,id+count,request);var task=tasks.get(owner,suite.items().getFirst().taskIds().getFirst()).data().request();
      assertEquals(request.references(),task.references());
      assertTrue(task.prompt().contains(id.equals("image-replica")?"效果参考":"成年模特参考"));
      if(count>2) { assertTrue(task.prompt().contains("前"+(count-1)+"张参考图是同一商品"));assertTrue(task.prompt().contains("最后一张参考图"));assertFalse(task.prompt().contains("第二张")); }
      assertEquals(task,request.task(module,template));
    }
    for(var template:List.of(ProductSuites.DETAIL_PAGE_V2,ProductSuites.APPAREL_SET)) {
      var modules=template.modules().stream().map(ModuleOption::id).toList();
      var r=toolRequest(template.id(),modules,null,List.of("file"));
      assertEquals(modules.size(),suites.submit(owner,template.id(),r).items().size());
    }
  }
  private Request toolRequest(String id,List<ProductSuites.Module> modules,Options options,List<String> files) {
    return new Request(id,id.equals("detail-page")?2:1,"商品","已确认资料","中文",files,modules,"image-model","3:4","1K",options);
  }
  @Test void rejectsModulesOutsideSelectedTemplateBeforeQuoteOrTaskCreation() {
    var invalid=new Request("apparel",1,"外套","","中文",List.of("product-file"),
      List.of(ProductSuites.Module.SELLING_POINTS),"image-model","3:4","1K");
    assertThrows(IllegalArgumentException.class,()->suites.quote(owner,invalid));
    assertThrows(IllegalArgumentException.class,()->suites.submit(owner,"wrong-module",invalid));
    verify(catalog,never()).quote(anyString(),any());
    assertTrue(suites.list(owner,20,null).isEmpty());
    assertTrue(tasks.list(owner,100,null,false).isEmpty());
  }
  @Test void persistsSixDistinctTasksAndReplaysAcrossRestartWithoutDuplicatingCharges() throws Exception {
    var request=request("服装套装");
    try(var pool=Executors.newFixedThreadPool(4)) {
      List<Future<Suite>> results=new ArrayList<>();
      for(int i=0;i<4;i++) results.add(pool.submit(()->suites.submit(owner,"same",request)));
      Set<String> ids=new HashSet<>();for(var result:results) ids.add(result.get().id());
      assertEquals(1,ids.size());
    }
    var suite=suites.submit(owner,"same",request);
    assertEquals(6,suite.items().size());assertEquals(6,tasks.list(owner,100,null,false).size());
    assertTrue(tasks.list(owner,100,null,true).isEmpty());
    var restarted=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock);
    assertEquals(suite,restarted.get(owner,suite.id()));
    assertEquals(suite,restarted.list(owner,20,null).getFirst());
    assertEquals(6,suite.items().stream().map(item->tasks.get(owner,item.taskIds().getLast()).data().request().prompt()).distinct().count());
    assertThrows(TaskConflictException.class,()->suites.submit(owner,"same",request("其他商品")));
    assertThrows(TaskNotFoundException.class,()->suites.get("other-owner",suite.id()));
    assertTrue(suites.list("other-owner",20,null).isEmpty());
    assertEquals("74.0736",suites.quote(owner,request).points());
  }
  @Test void childFailureRollsBackSuiteAndPreviouslyCreatedTasks() {
    jdbc.execute("ALTER TABLE media_product_suite_item ADD CONSTRAINT reject_details CHECK (module <> 'DETAILS')");
    try {
      assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->suites.submit(owner,"rollback",request("套装")));
      assertTrue(suites.list(owner,20,null).isEmpty());assertTrue(tasks.list(owner,100,null,false).isEmpty());
    } finally { jdbc.execute("ALTER TABLE media_product_suite_item DROP CONSTRAINT reject_details"); }
  }
  @Test void regenerationKeepsOriginalAndCannotRepeatWithAnotherKeyOrTarget() {
    var suite=suites.submit(owner,"start",request("套装"));var item=suite.items().getFirst();String original=item.taskIds().getFirst();
    assertThrows(TaskConflictException.class,()->suites.regenerate(owner,suite.id(),item.module(),original,"retry"));
    var source=tasks.get(owner,original);var lease=store.claim(original,"test",clock.instant(),clock.instant().plusSeconds(30)).orElseThrow();
    store.save(lease,source.advance(TaskStatus.FAILED,source.data(),clock.instant()),List.of(),clock.instant());
    assertThrows(TaskConflictException.class,()->tasks.regenerate(owner,original,"independent-retry",null));
    assertThrows(TaskConflictException.class,()->tasks.deleteHistory(owner,original));
    var updated=suites.regenerate(owner,suite.id(),item.module(),original,"retry");
    assertEquals(2,updated.items().getFirst().taskIds().size());
    assertEquals(updated,suites.regenerate(owner,suite.id(),item.module(),original,"retry"));
    assertThrows(TaskConflictException.class,()->suites.regenerate(owner,suite.id(),item.module(),original,"different"));
    assertThrows(TaskConflictException.class,()->suites.regenerate(owner,suite.id(),ProductSuites.Module.FEATURES,original,"retry"));
    assertThrows(TaskNotFoundException.class,()->suites.regenerate("other",suite.id(),item.module(),original,"retry"));
    assertEquals(7,tasks.list(owner,100,null,false).size());assertEquals(TaskStatus.FAILED,tasks.get(owner,original).status());
  }
  @Test void rejectsUnverifiedModelsForeignFilesDuplicateModulesAndInvalidTemplateBeforePersisting() {
    when(catalog.options(owner)).thenReturn(List.of());
    assertThrows(DefinitiveRejectionException.class,()->suites.submit(owner,"bad-model",request("套装")));
    setup();doThrow(new DefinitiveRejectionException("REFERENCE_REJECTED")).when(storage).resolveReferences(eq(owner),anyList());
    assertThrows(DefinitiveRejectionException.class,()->suites.submit(owner,"foreign-file",request("套装")));
    var r=request("套装");
    assertThrows(IllegalArgumentException.class,()->new Request("streetwear",1,r.productName(),r.sellingPoints(),r.language(),r.fileIds(),List.of(ProductSuites.Module.DETAILS,ProductSuites.Module.DETAILS),r.model(),r.aspectRatio(),r.resolution()));
    assertThrows(IllegalArgumentException.class,()->suites.submit(owner,"bad-template",new Request("missing",1,r.productName(),r.sellingPoints(),r.language(),r.fileIds(),r.modules(),r.model(),r.aspectRatio(),r.resolution())));
    assertTrue(suites.list(owner,20,null).isEmpty());
  }
  @Test void videoBreakdownValidatesTimesAndRejectsForeignFrameFiles() {
    assertThrows(IllegalArgumentException.class,()->new VideoBreakdownRequest("商品","","中文",List.of("a","b"),List.of(2.0,1.0),4));
    assertThrows(IllegalArgumentException.class,()->new VideoBreakdownRequest("商品","","中文",List.of("a"),List.of(Double.NaN),4));
    var request=new VideoBreakdownRequest("商品","","中文",List.of("a"),List.of(1.0),4);
    var writer=mock(com.xzwl.cloud.media.infrastructure.provider.ProductCopyWriter.class);when(writer.configured()).thenReturn(true);
    var configured=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
    doThrow(new DefinitiveRejectionException("REFERENCE_REJECTED")).when(storage).resolveReferences(eq(owner),anyList());
    assertThrows(DefinitiveRejectionException.class,()->configured.videoBreakdown(owner,request));
    verify(writer,never()).videoBreakdown(anyList(),any());
  }
  @Test void assistanceAuthorizesImagesButIgnoresLegacyDailyBudget() {
    var request=new CopyRequest("商品","","中文",List.of("product-file"));
    assertThrows(DependencyUnavailableException.class,()->suites.plan(owner,request));
    verifyNoInteractions(storage);
    var writer=mock(com.xzwl.cloud.media.infrastructure.provider.ProductCopyWriter.class);
    when(writer.configured()).thenReturn(true);
    var expected=new Plan(List.of(new ModuleCount(ProductSuites.Module.WHITE_BACKGROUND,1)));
    when(writer.plan(anyList(),anyString(),anyString(),anyString())).thenReturn(expected);
    when(writer.write(anyList(),anyString(),anyString(),anyString())).thenReturn("文案");
    var configured=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
    doThrow(new DefinitiveRejectionException("REFERENCE_REJECTED")).when(storage).resolveReferences(eq("foreign-owner"),anyList());
    assertThrows(DefinitiveRejectionException.class,()->configured.plan("foreign-owner",request));
    verify(writer,never()).plan(anyList(),anyString(),anyString(),anyString());
    jdbc.update("INSERT INTO media_product_copy_budget (owner_id,budget_day,attempts) VALUES (?,?,10)",owner,LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC).toString());
    for(int i=0;i<12;i++) configured.copy(owner,request);
    for(int i=0;i<12;i++) assertEquals(expected,configured.plan(owner,request));
    var restarted=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
    assertEquals(expected,restarted.plan(owner,request));
    assertEquals("文案",restarted.copy(owner,request).text());
    verify(writer,times(13)).write(anyList(),anyString(),anyString(),anyString());
    verify(writer,times(13)).plan(eq(List.of(new MediaReference(null,"https://files.example/product.jpg"))),eq("商品"),eq(""),eq("中文"));
  }
  @Test void concurrentProviderFailuresDoNotCreateADailyLimit() throws Exception {
    var writer=mock(com.xzwl.cloud.media.infrastructure.provider.ProductCopyWriter.class);
    when(writer.configured()).thenReturn(true);
    when(writer.write(anyList(),anyString(),anyString(),anyString())).thenThrow(new DependencyUnavailableException("unavailable"));
    var configured=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
    var request=new CopyRequest("套装","","中文",List.of("product-file"));
    try(var pool=Executors.newFixedThreadPool(6)) {
      List<Future<Boolean>> results=new ArrayList<>();
      for(int i=0;i<16;i++) results.add(pool.submit(()->{
        try { configured.copy(owner,request);fail("writer must fail");return false; }
        catch(DependencyUnavailableException called) { return true; }
      }));
      int calls=0;for(var result:results) if(result.get()) calls++;
      assertEquals(16,calls);
    }
    verify(writer,times(16)).write(anyList(),anyString(),anyString(),anyString());
    var restarted=new JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
    assertThrows(DependencyUnavailableException.class,()->restarted.copy(owner,request));
    verify(writer,times(17)).write(anyList(),anyString(),anyString(),anyString());
    verify(storage,atLeastOnce()).resolveReferences(eq(owner),eq(List.of(new MediaReference("product-file",null))));
  }
}
