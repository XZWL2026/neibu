package com.xzwl.cloud.media.application.catalog;

import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.util.*;

/** Routing owns capabilities; Billing owns owner eligibility and all price calculations. */
public final class GenerationCatalogService {
  private final RoutingPort routing;
  private final BillingPort billing;
  private final StoragePort storage;
  private final PortraitReviews portraits;
  public GenerationCatalogService(RoutingPort routing,BillingPort billing) {
    this(routing,billing,null);
  }
  public GenerationCatalogService(RoutingPort routing,BillingPort billing,StoragePort storage) {
    this(routing,billing,storage,null);
  }
  public GenerationCatalogService(RoutingPort routing,BillingPort billing,StoragePort storage,PortraitReviews portraits) {
    this.routing=routing; this.billing=billing; this.storage=storage; this.portraits=portraits;
  }
  public List<Option> options(String owner) {
    var prices=billing.availablePrices(owner);
    List<Option> options=new ArrayList<>();
    for (var model : routing.models()) {
      try {
        Option capability=capability(model);
        List<String> resolutions=capability.resolutions().stream().filter(resolution -> prices.stream().anyMatch(price ->
            model.modelId().equals(price.model()) && resolution.equals(price.resolution())
                && (price.type()==null || price.type().equals(capability.type().name()))
                && price.amountPerUnit()!=null && price.amountPerUnit().signum()>=0)).sorted().toList();
        if (!resolutions.isEmpty()) options.add(new Option(capability.type(),model.modelId(),capability.displayName(),
            resolutions,capability.aspectRatios(),capability.maxCount(),capability.minDurationSeconds(),capability.maxDurationSeconds(),
            capability.durationStepSeconds(),capability.maxReferenceImages(),capability.referenceFollowsFirstFrame(),capability.omniReference()));
      } catch (IllegalArgumentException | NullPointerException invalidConfiguration) {
        // An incomplete model is not an offer; other configured models remain available.
      }
    }
    return options.stream().sorted(Comparator.comparing(Option::model)).toList();
  }
  public void validate(String owner,TaskRequest request) {
    boolean supported=options(owner).stream().anyMatch(option -> option.type()==request.type()
        && option.model().equals(request.model()) && option.resolutions().contains(request.resolution())
        && option.aspectRatios().contains(request.aspectRatio()) && request.count()<=option.maxCount()
        && request.durationSeconds()>=option.minDurationSeconds() && request.durationSeconds()<=option.maxDurationSeconds()
        && (request.referenceMode()==ReferenceMode.OMNI
            ? option.omniReference()!=null && option.omniReference().accepts(request.references(),false)
                && request.references().stream().allMatch(ref -> ref.fileId()!=null)
            : ((request.type()!=MediaType.VIDEO && option.maxReferenceImages()==0) || request.references().size()<=option.maxReferenceImages())));
    if (!supported) throw new DefinitiveRejectionException("UNSUPPORTED_GENERATION_SELECTION");
    if(portraits==null) PortraitReviews.rejectUnsupported(request);
    else portraits.validate(owner,request);
  }
  public BigDecimal quote(String owner,TaskRequest request) {
    validate(owner,request);
    if(request.referenceMode()==ReferenceMode.OMNI) {
      if(storage==null) throw new DependencyUnavailableException("reference metadata unavailable");
      var refs=storage.resolveReferences(owner,request.references(),request.referenceMode());
      var option=options(owner).stream().filter(o -> o.model().equals(request.model())).findFirst().orElseThrow();
      if(!option.omniReference().accepts(refs,true)) throw new DefinitiveRejectionException("UNSUPPORTED_OMNI_REFERENCE");
    }
    return billing.quote(owner,request);
  }
  private static Option capability(RoutingPort.Model model) {
    MediaReference.identifier(model.modelId(),"model");
    if(Set.of("image2","gpt-image2","gemini-3-pro-image-preview","gemini-3.1-flash-image-preview").contains(model.providerModel()))
      throw new IllegalArgumentException("synchronous-only model is not offered for new tasks");
    var c=Objects.requireNonNull(model.configuration());
    var fields=new HashSet<>(c.keySet());
    fields.remove("sizes");
    if(fields.remove("requestPolicy")) {
      if(!(c.get("requestPolicy") instanceof Map<?,?> policy) || !Set.of("timeoutSeconds","retryCount").containsAll(policy.keySet()))
        throw new IllegalArgumentException("invalid request policy");
      for(var entry:policy.entrySet()) {
        if(entry.getValue()==null) continue;
        if(!(entry.getValue() instanceof Integer value) || (entry.getKey().equals("timeoutSeconds") ? value<1 || value>180 : value<0 || value>3))
          throw new IllegalArgumentException("invalid request policy");
      }
    }
    if (!fields.equals(Set.of("type","displayName","resolutions","aspectRatios","maxCount","minDurationSeconds","maxDurationSeconds")))
      throw new IllegalArgumentException("invalid capability fields");
    MediaType type=MediaType.valueOf(text(c,"type"));
    String displayName=MediaReference.text(text(c,"displayName"),"displayName",128);
    List<String> resolutions=strings(c,"resolutions");
    resolutions.forEach(resolution -> MediaReference.identifier(resolution,"resolution"));
    List<String> ratios=strings(c,"aspectRatios");
    if (!List.of("1:1","16:9","9:16","4:3","3:4","21:9").containsAll(ratios)) throw new IllegalArgumentException("invalid aspect ratios");
    int maxCount=integer(c,"maxCount"), min=integer(c,"minDurationSeconds"), max=integer(c,"maxDurationSeconds");
    if (type==MediaType.IMAGE ? maxCount<1 || maxCount>4 || min!=0 || max!=0
        : maxCount!=1 || min<1 || max<min || max>600) throw new IllegalArgumentException("invalid limits");
    // maxReferenceImages retains first/last-frame semantics for old clients.
    // Exact-version support: official Ark creation API 1520757, reviewed 2026-09-09.
    int references=type==MediaType.IMAGE ? switch(model.providerModel()) {
      case "gpt-image-2","gpt-image-2-2K","gpt-image-2-4K","gpt-image-2.5","gpt-image-2.5-flare","gpt-image-2.5-sunburst","nano_banana_2","nano_banana_2-2K","nano_banana_2-4K","nano_banana_pro-1K","nano_banana_pro-2K","nano_banana_pro-4K" -> 8;
      default -> 0;
    } : switch(model.providerModel()) {
      case "doubao-seedance-2-5-260628", "doubao-seedance-2-0-260128",
          "doubao-seedance-2-0-fast-260128", "doubao-seedance-2-0-mini-260615",
          "doubao-seedance-1-5-pro-251215", "doubao-seedance-1-0-pro-250528" -> 2;
      case "doubao-seedance-1-0-pro-fast-251015" -> 1;
      case "omni_flash-10s-fl" -> 2;
      default -> 0;
    };
    return new Option(type,model.modelId(),displayName,resolutions,ratios,maxCount,min,max,
        type==MediaType.VIDEO ? 1 : 0,references,
        type==MediaType.VIDEO && "doubao-seedance-2-5-260628".equals(model.providerModel()),
        type==MediaType.VIDEO ? OmniReference.forModel(model.providerModel()) : null);
  }
  private static String text(Map<String,Object> config,String key) {
    if (!(config.get(key) instanceof String value)) throw new IllegalArgumentException("invalid text");
    return value;
  }
  private static int integer(Map<String,Object> config,String key) {
    if (!(config.get(key) instanceof Integer value)) throw new IllegalArgumentException("invalid integer");
    return value;
  }
  private static List<String> strings(Map<String,Object> config,String key) {
    if (!(config.get(key) instanceof List<?> values) || values.isEmpty()) throw new IllegalArgumentException("invalid list");
    List<String> result=new ArrayList<>();
    for (var value : values) {
      if (!(value instanceof String text) || text.isBlank() || result.contains(text)) throw new IllegalArgumentException("invalid list item");
      result.add(text);
    }
    return List.copyOf(result);
  }
  public record Option(MediaType type,String model,String displayName,List<String> resolutions,List<String> aspectRatios,
      int maxCount,int minDurationSeconds,int maxDurationSeconds,
      int durationStepSeconds,int maxReferenceImages,boolean referenceFollowsFirstFrame,OmniReference omniReference) {}
}
