package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.provider.ArkModelCatalog.Capability;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.util.*;

/** ZEX's documented JSON interfaces; not a blanket OpenAI/Ark compatibility assumption. */
final class ZexMediaAdapter {
  private final ProviderHttp http;
  ZexMediaAdapter(ProviderHttp http) { this.http=http; }
  private static final List<String> RATIOS=List.of("1:1","16:9","9:16","4:3","3:4","21:9");
  private static boolean gemini(String model) {
    return Set.of("gemini-3-pro-image-preview","gemini-3.1-flash-image-preview").contains(model);
  }
  private static final Map<String,List<String>> ASYNC_IMAGES=Map.ofEntries(
      Map.entry("gpt-image-2",List.of("1K")),Map.entry("gpt-image-2-2K",List.of("2K")),Map.entry("gpt-image-2-4K",List.of("4K")),
      Map.entry("gpt-image-2.5",List.of("1K")),Map.entry("gpt-image-2.5-flare",List.of("1K","2K","4K")),
      Map.entry("gpt-image-2.5-sunburst",List.of("1K","2K","4K")),
      Map.entry("nano_banana_2",List.of("1K")),Map.entry("nano_banana_2-2K",List.of("2K")),Map.entry("nano_banana_2-4K",List.of("4K")),
      Map.entry("nano_banana_pro-1K",List.of("1K")),Map.entry("nano_banana_pro-2K",List.of("2K")),Map.entry("nano_banana_pro-4K",List.of("4K")));
  static boolean asynchronousImage(String model) { return ASYNC_IMAGES.containsKey(model); }
  static boolean synchronousOnly(String model) { return gemini(model) || Set.of("image2","gpt-image2").contains(model); }
  static Capability capability(String model) {
    if(asynchronousImage(model)) return new Capability(ASYNC_IMAGES.get(model),RATIOS,1,0,0,null);
    if(Set.of("omni_flash-10s","omni_flash-10s-fl").contains(model))
      return new Capability(List.of("720p"),List.of("16:9","9:16"),1,10,10,null);
    return null;
  }
  static ProviderSettings.Plan plan(String base,TaskRequest request,String model) {
    var c=capability(model);
    if(c==null) throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_MODEL");
    boolean video=c.minDurationSeconds()>0;
    if(video!=(request.type()==MediaType.VIDEO) || !c.resolutions().contains(request.resolution())
        || !c.aspectRatios().contains(request.aspectRatio()) || request.count()!=1
        || request.references().size()>(video?2:8) || (video && request.durationSeconds()!=10)
        || (model.equals("omni_flash-10s") && !request.references().isEmpty())
        || (model.startsWith("nano_banana_") && request.prompt().length()>10000)
        || (model.endsWith("-fl") && request.references().isEmpty()))
      throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_REQUEST");
    String size=video ? (request.aspectRatio().equals("16:9") ? "1280x720" : "720x1280")
        : request.resolution();
    return new ProviderSettings.Plan(base,model,request.type(),size,request.resolution(),request.aspectRatio(),null,null,null,null,0);
  }
  String submitImage(TaskRequest request,ProviderSettings.Plan plan,String key) {
    var body=new LinkedHashMap<String,Object>();
    body.put("model",plan.model());body.put("prompt",request.prompt());body.put("aspect_ratio",plan.aspectRatio());
    if(Set.of("gpt-image-2.5-flare","gpt-image-2.5-sunburst").contains(plan.model()))
      body.put("image_size",plan.targetResolution());
    if(!request.references().isEmpty()) body.put("images",request.references().stream().map(MediaReference::httpsUrl).toList());
    return ArkMediaAdapter.taskId(call(plan,"POST","/v1/videos",key,body).path("id"));
  }
  // Recovery only for immutable synchronous plans accepted before async-only model admission.
  List<ProviderPort.ProviderOutput> image(TaskRequest request,ProviderSettings.Plan plan,String key) {
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("response_format","url");
    String path;
    if(gemini(plan.model())) {
      var parts=new ArrayList<Object>();
      parts.add(Map.of("text",request.prompt()));
      request.references().forEach(ref->parts.add(Map.of("fileData",Map.of("fileUri",ref.httpsUrl()))));
      body.put("contents",List.of(Map.of("role","user","parts",parts)));
      body.put("generationConfig",Map.of("responseModalities",List.of("IMAGE"),
          "imageConfig",Map.of("aspectRatio",plan.aspectRatio(),"imageSize",plan.targetResolution())));
      path="/v1beta/models/"+plan.model()+":generateContent";
    } else {
      body.put("model",plan.model());body.put("prompt",request.prompt());body.put("size",plan.sourceResolution());
      if(!request.references().isEmpty()) body.put("image",request.references().stream().map(MediaReference::httpsUrl).toList());
      path=request.references().isEmpty() ? "/v1/images/generations" : "/v1/images/edits";
    }
    JsonNode result=call(plan,"POST",path,key,body);
    var urls=new ArrayList<String>();
    if(result.path("data").isArray()) {
      for(var item:result.path("data")) if(item.path("url").isTextual()) urls.add(item.path("url").asText());
    } else if(gemini(plan.model()) && result.path("candidates").isArray()) {
      for(var candidate:result.path("candidates")) for(var part:candidate.path("content").path("parts"))
        if(part.path("image_url").path("url").isTextual()) urls.add(part.path("image_url").path("url").asText());
    } else throw ProviderHttp.uncertain();
    if(urls.size()>request.count()) throw ProviderHttp.uncertain();
    var outputs=new ArrayList<ProviderPort.ProviderOutput>();
    for(int i=0;i<urls.size();i++) {
      try { outputs.add(new ProviderPort.ProviderOutput(i,urls.get(i))); }
      catch(IllegalArgumentException unsafe) { /* Unsafe output fails independently. */ }
    }
    return outputs;
  }
  String submitVideo(TaskRequest request,ProviderSettings.Plan plan,String key) {
    var body=new LinkedHashMap<String,Object>();
    body.put("model",plan.model());body.put("prompt",request.prompt());body.put("size",plan.sourceResolution());
    if(!request.references().isEmpty()) body.put("images",request.references().stream().map(MediaReference::httpsUrl).toList());
    return ArkMediaAdapter.taskId(call(plan,"POST","/v1/videos",key,body).path("id"));
  }
  ProviderPort.ProviderPoll poll(String id,ProviderSettings.Plan plan,String key) {
    var result=call(plan,"GET","/v1/videos/"+ArkMediaAdapter.taskId(id),key,null);
    if(result.hasNonNull("id") && !id.equals(result.path("id").asText())) throw ProviderHttp.uncertain();
    return switch(result.path("status").asText()) {
      case "queued","processing","in_progress" -> ProviderPort.ProviderPoll.pending();
      case "failed" -> ProviderPort.ProviderPoll.failed(result.path("error").path("code").asText("PROVIDER_FAILED"),"failed");
      case "completed" -> {
        String url=plan.type()==MediaType.IMAGE
            ? result.path("url").asText(result.path("metadata").path("image_url").asText(result.path("metadata").path("image").asText("")))
            : result.path("video_url").asText(result.path("url").asText(""));
        try { yield ProviderPort.ProviderPoll.completed(List.of(new ProviderPort.ProviderOutput(0,url))); }
        catch(IllegalArgumentException invalid) { throw ProviderHttp.uncertain(); }
      }
      default -> throw ProviderHttp.uncertain();
    };
  }
  private JsonNode call(ProviderSettings.Plan plan,String method,String path,String key,Object body) {
    if(key.isBlank()) throw ProviderSettings.unavailable();
    var base=ProviderSettings.providerEndpoint(plan.arkBase());
    String origin=base.getScheme()+"://"+base.getRawAuthority();
    var result=http.call(method,origin+path,Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),
        body==null ? null : http.encode(body),plan.timeoutSeconds(),plan.retryCount());
    if(result.hasNonNull("error") && !"failed".equals(result.path("status").asText())) {
      if(method.equals("GET")) throw ProviderHttp.uncertain();
      throw new DefinitiveRejectionException("PROVIDER_REJECTED");
    }
    return result;
  }
}
