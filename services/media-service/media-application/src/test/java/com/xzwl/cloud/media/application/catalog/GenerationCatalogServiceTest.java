package com.xzwl.cloud.media.application.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class GenerationCatalogServiceTest {
  static Map<String,Object> capability() {
    return Map.of("type","IMAGE","displayName","Image","resolutions",List.of("2K","4K"),
        "aspectRatios",List.of("1:1","16:9"),"maxCount",4,"minDurationSeconds",0,"maxDurationSeconds",0);
  }
  final List<RoutingPort.Model> models = new ArrayList<>();
  final List<BillingPort.AvailablePrice> prices = new ArrayList<>();
  int quotes;
  GenerationCatalogService catalog() { return catalog(null); }
  GenerationCatalogService catalog(StoragePort storage) {
    return new GenerationCatalogService(new RoutingPort() {
      public RouteSnapshot route(String id,String model) { throw new AssertionError("catalog must not route a task"); }
      public List<Model> models() { return models; }
    }, new BillingPort() {
      public List<AvailablePrice> availablePrices(String owner) { assertEquals("owner",owner); return prices; }
      public BigDecimal quote(String owner,TaskRequest request) { quotes++; return new BigDecimal("12.3400"); }
      public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) { throw new AssertionError("catalog must not charge"); }
      public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) { throw new AssertionError("catalog must not refund"); }
    },storage);
  }
  @Test void requestPolicyDoesNotHideValidCapabilitiesAndMalformedPolicyIsRejected() {
    var config=new HashMap<>(capability());
    config.put("requestPolicy",Map.of("timeoutSeconds",30,"retryCount",2));
    models.add(new RoutingPort.Model("image","upstream",config));
    prices.add(new BillingPort.AvailablePrice("image","2K",BigDecimal.ONE));
    assertEquals(1,catalog().options("owner").size());
    for(Object policy:List.of(Map.of("timeoutSeconds",181),Map.of("retryCount",4),Map.of("timeoutSeconds","30"),Map.of("other",1),"invalid")) {
      config.put("requestPolicy",policy);
      assertTrue(catalog().options("owner").isEmpty());
    }
  }
  @Test void intersectsOwnerPricesWithCapabilitiesKeepingFreePricesInStableOrder() {
    for (String id : List.of("z-model","a-model","unpriced")) models.add(new RoutingPort.Model(id,"upstream",capability()));
    prices.add(new BillingPort.AvailablePrice("z-model","2K",BigDecimal.ONE));
    prices.add(new BillingPort.AvailablePrice("a-model","4K",BigDecimal.ZERO));
    prices.add(new BillingPort.AvailablePrice("a-model","2K",null));
    prices.add(new BillingPort.AvailablePrice("not-routed","2K",BigDecimal.ONE));
    var options = catalog().options("owner");
    assertEquals(List.of("a-model","z-model"),options.stream().map(GenerationCatalogService.Option::model).toList());
    assertEquals(List.of("4K"),options.getFirst().resolutions());
  }
  @Test void offersConfiguredUltrawideRatio() {
    var config=new HashMap<>(capability()); config.put("aspectRatios",List.of("1:1","21:9"));
    models.add(new RoutingPort.Model("wide","gpt-image-2.5",config));
    prices.add(new BillingPort.AvailablePrice("wide","2K",BigDecimal.ONE));
    assertEquals(List.of("1:1","21:9"),catalog().options("owner").getFirst().aspectRatios());
  }
  @Test void ignoresIncompleteAndIllegalCapabilityFields() {
    var invalid = new ArrayList<Map<String,Object>>();
    for (String field : capability().keySet()) { var c=new HashMap<>(capability()); c.remove(field); invalid.add(c); }
    for (var entry : Map.<String,Object>of("maxCount",5,"type","AUDIO","aspectRatios",List.of("7:2"),
        "resolutions",List.of(),"minDurationSeconds",-1,"maxDurationSeconds",3,"displayName"," ").entrySet()) {
      var c=new HashMap<>(capability()); c.put(entry.getKey(),entry.getValue()); invalid.add(c);
    }
    int i=0;
    for (var config : invalid) {
      String id="bad-"+i++; models.add(new RoutingPort.Model(id,"upstream",config));
      prices.add(new BillingPort.AvailablePrice(id,"2K",BigDecimal.ONE));
    }
    assertTrue(catalog().options("owner").isEmpty());
  }
  @Test void validatesTheSameSelectionBeforeQuotingOrSubmitting() {
    models.add(new RoutingPort.Model("image","upstream",capability()));
    prices.add(new BillingPort.AvailablePrice("image","2K",BigDecimal.ONE));
    var catalog=catalog();
    var valid=new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","2K",4,0,List.of());
    assertEquals(new BigDecimal("12.3400"),catalog.quote("owner",valid));
    assertDoesNotThrow(() -> catalog.validate("owner",valid));
    for (var invalid : List.of(
        new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","4K",1,0,List.of()),
        new TaskRequest(MediaType.IMAGE,"image","prompt","9:16","2K",1,0,List.of()),
        new TaskRequest(MediaType.VIDEO,"image","prompt","16:9","2K",1,5,List.of())))
      assertThrows(DefinitiveRejectionException.class,() -> catalog.quote("owner",invalid));
    assertEquals(1,quotes);
  }
  @Test void videoDurationMustStayInsideTheConfiguredCapability() {
    models.add(new RoutingPort.Model("video","upstream",Map.of("type","VIDEO","displayName","Video",
        "resolutions",List.of("720p"),"aspectRatios",List.of("16:9"),"maxCount",1,
        "minDurationSeconds",5,"maxDurationSeconds",15)));
    prices.add(new BillingPort.AvailablePrice("video","720p",BigDecimal.ONE));
    assertDoesNotThrow(() -> catalog().validate("owner",
        new TaskRequest(MediaType.VIDEO,"video","prompt","16:9","720p",1,5,List.of())));
    assertThrows(DefinitiveRejectionException.class,() -> catalog().quote("owner",
        new TaskRequest(MediaType.VIDEO,"video","prompt","16:9","720p",1,16,List.of())));
    assertEquals(0,quotes);
  }
  @Test void zexModelsExposeImageReferencesAndOmniFirstLastFrames() {
    for(String model:List.of("gpt-image-2","gpt-image-2-2K","gpt-image-2-4K","gpt-image-2.5","gpt-image-2.5-flare","gpt-image-2.5-sunburst","nano_banana_2","nano_banana_2-2K","nano_banana_2-4K","nano_banana_pro-1K","nano_banana_pro-2K","nano_banana_pro-4K")) {
      models.clear();prices.clear();models.add(new RoutingPort.Model("image",model,capability()));
      prices.add(new BillingPort.AvailablePrice("image","2K",BigDecimal.ONE));
      assertEquals(8,catalog().options("owner").getFirst().maxReferenceImages());
    }
    models.clear();prices.clear();
    var config=Map.<String,Object>of("type","VIDEO","displayName","Omni","resolutions",List.of("720p"),
        "aspectRatios",List.of("16:9"),"maxCount",1,"minDurationSeconds",10,"maxDurationSeconds",10);
    models.add(new RoutingPort.Model("omni","omni_flash-10s-fl",config));
    prices.add(new BillingPort.AvailablePrice("omni","720p",BigDecimal.ONE));
    assertEquals(2,catalog().options("owner").getFirst().maxReferenceImages());
    var ref=new MediaReference("image-file",null);
    assertDoesNotThrow(()->catalog().quote("owner",new TaskRequest(MediaType.VIDEO,"omni","prompt","720p",1,10,List.of(ref,ref))));
    assertThrows(DefinitiveRejectionException.class,()->catalog().quote("owner",new TaskRequest(MediaType.VIDEO,"omni","prompt","720p",1,10,List.of(ref,ref,ref))));
  }
  @Test void rejectsVideoReferenceModesTheSelectedProviderModelDoesNotSupport() {
    var config=Map.<String,Object>of("type","VIDEO","displayName","Fast",
        "resolutions",List.of("720p"),"aspectRatios",List.of("16:9"),"maxCount",1,
        "minDurationSeconds",2,"maxDurationSeconds",12);
    models.add(new RoutingPort.Model("fast","doubao-seedance-1-0-pro-fast-251015",config));
    prices.add(new BillingPort.AvailablePrice("fast","720p",BigDecimal.ONE));
    var image=new MediaReference("image-file",null);
    var request=new TaskRequest(MediaType.VIDEO,"fast","prompt","16:9","720p",1,5,List.of(image,image));
    assertThrows(DefinitiveRejectionException.class,()->catalog().quote("owner",request));
    assertEquals(0,quotes);
    assertEquals(1,catalog().options("owner").getFirst().durationStepSeconds());
    assertEquals(1,catalog().options("owner").getFirst().maxReferenceImages());
    assertFalse(catalog().options("owner").getFirst().referenceFollowsFirstFrame());
    assertDoesNotThrow(()->catalog().validate("owner",request.withReferences(List.of(image))));
  }

  @Test void rejectsSyncModelsAndExcessAsyncImageReferencesBeforeQuoting() {
    for(String model:List.of("image2","gpt-image2","gemini-3-pro-image-preview","gemini-3.1-flash-image-preview")) {
      models.clear();prices.clear();
      models.add(new RoutingPort.Model("image",model,capability()));
      prices.add(new BillingPort.AvailablePrice("image","2K",BigDecimal.ONE));
      assertTrue(catalog().options("owner").isEmpty());
      assertThrows(DefinitiveRejectionException.class,()->catalog().quote("owner",
          new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","2K",1,0,List.of())));
    }
    models.clear();
    models.add(new RoutingPort.Model("image","gpt-image-2-2K",capability()));
    var ref=new MediaReference("image-file",null);
    assertThrows(DefinitiveRejectionException.class,()->catalog().quote("owner",
        new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","2K",1,0,Collections.nCopies(9,ref))));
    assertEquals(0,quotes);
    assertDoesNotThrow(()->catalog().validate("owner",
        new TaskRequest(MediaType.IMAGE,"image","prompt","1:1","2K",1,0,Collections.nCopies(8,ref))));
  }

  @Test void omniQuoteUsesStorageDurationAndRejectsUnownedUrlsBeforeBilling() {
    var config=Map.<String,Object>of("type","VIDEO","displayName","Seedance",
        "resolutions",List.of("720p"),"aspectRatios",List.of("16:9"),"maxCount",1,
        "minDurationSeconds",2,"maxDurationSeconds",15);
    models.add(new RoutingPort.Model("video","doubao-seedance-2-0-260128",config));
    prices.add(new BillingPort.AvailablePrice("video","720p",BigDecimal.ONE));
    var resolvedDuration=new double[]{4};
    StoragePort storage=new StoragePort() {
      public List<MediaReference> resolveReferences(String owner,List<MediaReference> refs) {
        assertEquals("owner",owner); assertEquals("video-file",refs.getFirst().fileId());
        return List.of(new MediaReference(null,"https://files.example/video.mp4",MediaReference.Kind.VIDEO,resolvedDuration[0]));
      }
      public String archive(String o,String t,int i,String u) { throw new AssertionError("quote must not archive"); }
    };
    var request=new TaskRequest(MediaType.VIDEO,"video","prompt","16:9","720p",1,5,
        List.of(new MediaReference("video-file",null,MediaReference.Kind.VIDEO,2.0)),ReferenceMode.OMNI);
    assertEquals(new BigDecimal("12.3400"),catalog(storage).quote("owner",request));
    resolvedDuration[0]=18;
    assertThrows(DefinitiveRejectionException.class,()->catalog(storage).quote("owner",request));
    assertThrows(DefinitiveRejectionException.class,()->catalog(storage).quote("owner",request.withReferences(
        List.of(new MediaReference(null,"https://unverified.example/video.mp4",MediaReference.Kind.VIDEO,2.0)))));
    assertEquals(1,quotes);
    assertEquals(9,catalog().options("owner").getFirst().omniReference().maxImages());
    models.clear(); models.add(new RoutingPort.Model("video","doubao-seedance-2-5-260628",config));
    assertTrue(catalog().options("owner").getFirst().omniReference().audioOnly());
    assertEquals(new BigDecimal("12.3400"),catalog(storage).quote("owner",request));
    models.clear(); models.add(new RoutingPort.Model("video","omni_flash-10s-fl",config));
    assertNull(catalog().options("owner").getFirst().omniReference());
    assertThrows(DefinitiveRejectionException.class,()->catalog(storage).quote("owner",request));
  }

}
