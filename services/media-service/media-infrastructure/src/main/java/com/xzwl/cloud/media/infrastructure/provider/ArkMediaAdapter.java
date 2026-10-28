package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.*;

public final class ArkMediaAdapter {
  private final ProviderHttp http;
  public ArkMediaAdapter(ProviderHttp http) { this.http=http; }
  List<ProviderPort.ProviderOutput> image(TaskRequest request,ProviderSettings.Plan plan,String key) {
    if(ProviderSettings.zex(plan.arkBase())) return new ZexMediaAdapter(http).image(request,plan,key);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("model",plan.model()); body.put("prompt",request.prompt()); body.put("size",plan.sourceResolution());
    body.put("response_format","url"); body.put("watermark",false);
    if(!"doubao-seedream-5-0-pro-260628".equals(plan.model())) {
      body.put("stream",false);
      body.put("sequential_image_generation",request.count()>1?"auto":"disabled");
      if(request.count()>1) body.put("sequential_image_generation_options",Map.of("max_images",request.count()));
    }
    if(!request.references().isEmpty()) body.put("image",request.references().stream().map(MediaReference::httpsUrl).toList());
    JsonNode result=call(plan,"POST","/images/generations",key,body);
    if(result.hasNonNull("error")) throw new DefinitiveRejectionException(result.path("error").path("code").asText("PROVIDER_REJECTED"));
    JsonNode data=result.path("data");
    if(!data.isArray() || data.size()>request.count()) throw ProviderHttp.uncertain();
    List<ProviderPort.ProviderOutput> outputs=new ArrayList<>();
    for(int i=0;i<data.size();i++) {
      JsonNode item=data.get(i);
      if(item.hasNonNull("error") || item.hasNonNull("b64_json")) continue;
      String url=item.path("url").asText("");
      try { outputs.add(new ProviderPort.ProviderOutput(i,url)); }
      catch(IllegalArgumentException unsafe) { /* This output fails independently; never persist unsafe media. */ }
    }
    return outputs;
  }
  String submitImage(TaskRequest request,ProviderSettings.Plan plan,String key) {
    return new ZexMediaAdapter(http).submitImage(request,plan,key);
  }
  String submitVideo(TaskRequest request,ProviderSettings.Plan plan,String key) {
    return submitVideo(request,plan,key,Map.of(),null);
  }
  String submitVideo(TaskRequest request,ProviderSettings.Plan plan,String key,Map<Integer,String> assets,String project) {
    boolean maas=MaasMediaAdapter.matches(plan.arkBase());
    if(!assets.isEmpty()) {
      var endpoint=ProviderSettings.arkEndpoint(plan.arkBase());
      boolean official=endpoint.getScheme().equals("https") && endpoint.getHost().equals("ark.cn-beijing.volces.com")
          && endpoint.getPort()==-1 && endpoint.getPath().equals("/api/v3") && OmniReference.forModel(plan.model())!=null;
      if(!(official || maas && MaasMediaAdapter.supports(plan.model()))
          || request.referenceMode()!=ReferenceMode.OMNI || request.type()!=MediaType.VIDEO)
        throw new DefinitiveRejectionException("PORTRAIT_PROVIDER_UNSUPPORTED");
      for(var asset:assets.entrySet()) {
        if(asset.getKey()<0 || asset.getKey()>=request.references().size())
          throw new DefinitiveRejectionException("INVALID_PORTRAIT_REFERENCE");
        taskId(asset.getValue());
      }
    }
    if(ProviderSettings.zex(plan.arkBase())) return new ZexMediaAdapter(http).submitVideo(request,plan,key);
    List<Object> content=new ArrayList<>(); content.add(Map.of("type","text","text",request.prompt()));
    for(int i=0;i<request.references().size();i++) {
      var ref=request.references().get(i);
      String kind=ref.kind().name().toLowerCase(java.util.Locale.ROOT);
      String type=kind+"_url";
      String role=request.referenceMode()==ReferenceMode.OMNI ? "reference_"+kind : i==0?"first_frame":"last_frame";
      content.add(Map.of("type",type,type,Map.of("url",assets.containsKey(i)?"asset://"+assets.get(i):ref.httpsUrl()),"role",role));
    }
    // Seedance 2.5 first/last-frame tasks require the source image's aspect ratio.
    String ratio=request.referenceMode()!=ReferenceMode.OMNI && "doubao-seedance-2-5-260628".equals(plan.model()) && !request.references().isEmpty()
        ? "adaptive" : request.aspectRatio();
    var body=new LinkedHashMap<String,Object>(Map.of("model",plan.model(),"content",content,
        "resolution",plan.sourceResolution(),"duration",request.durationSeconds(),"ratio",ratio));
    if(maas && !assets.isEmpty()) body.put("ProjectName",MediaReference.identifier(project,"projectName"));
    if(request.referenceMode()==ReferenceMode.OMNI && "doubao-seedance-2-5-260628".equals(plan.model()))
      body.put("omni_reference_task_type","reference");
    JsonNode result=call(plan,"POST","/contents/generations/tasks",key,body);
    if(result.hasNonNull("error")) throw new DefinitiveRejectionException(result.path("error").path("code").asText("PROVIDER_REJECTED"));
    return taskId(maas ? receipt(result) : result.path("id"));
  }
  ProviderPort.ProviderPoll poll(String id,ProviderSettings.Plan plan,String key) {
    if(ProviderSettings.zex(plan.arkBase())) return new ZexMediaAdapter(http).poll(id,plan,key);
    JsonNode result=call(plan,"GET","/contents/generations/tasks/"+taskId(id),key,null);
    boolean maas=MaasMediaAdapter.matches(plan.arkBase());
    JsonNode receipt=maas ? receipt(result) : result.path("id");
    if(!receipt.isMissingNode() && !receipt.isNull() && !id.equals(receipt.asText())) throw ProviderHttp.uncertain();
    if(maas && !result.hasNonNull("status") && result.path("data").isObject()) {
      result=result.path("data");
      JsonNode nestedReceipt=receipt(result);
      if(!nestedReceipt.isMissingNode() && !nestedReceipt.isNull() && !id.equals(nestedReceipt.asText())) throw ProviderHttp.uncertain();
    }
    return switch(result.path("status").asText("")) {
      case "queued","running" -> ProviderPort.ProviderPoll.pending();
      case "failed","expired","cancelled" -> ProviderPort.ProviderPoll.failed(result.path("error").path("code").asText(
          "PROVIDER_FAILED"),result.path("status").asText());
      case "succeeded","completed" -> {
        try { yield new ProviderPort.ProviderPoll(ProviderPort.ProviderPoll.Status.COMPLETED,
            List.of(new ProviderPort.ProviderOutput(0,videoUrl(result,maas))),null,null,
            tokens(result.path("usage").path("completion_tokens")),resolution(result.path("resolution"))); }
        catch(IllegalArgumentException invalid) { yield ProviderPort.ProviderPoll.failed(); }
      }
      default -> throw ProviderHttp.uncertain();
    };
  }
  private static JsonNode receipt(JsonNode result) {
    if(result.hasNonNull("task_id")) return result.path("task_id");
    if(result.hasNonNull("id")) return result.path("id");
    return result.path("data").hasNonNull("task_id") ? result.path("data").path("task_id") : result.path("data").path("id");
  }
  private static String videoUrl(JsonNode result,boolean maas) {
    String url=result.path("content").path("video_url").asText("");
    if(maas && url.isBlank()) url=result.path("video_url").asText("");
    if(maas && url.isBlank()) url=result.path("official_url").asText("");
    return url;
  }
  private static Long tokens(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0 ? value.longValue() : null;
  }
  private static String resolution(JsonNode value) {
    return value.isTextual() && value.textValue().matches("[0-9]{2,5}[pPkK]|[0-9]{1,5}[xX][0-9]{1,5}|[1248][kK]") ? value.textValue() : null;
  }
  private JsonNode call(ProviderSettings.Plan plan,String method,String path,String key,Object body) {
    if(key.isBlank()) throw ProviderSettings.unavailable();
    String base=ProviderSettings.arkEndpoint(plan.arkBase()).toString();
    return http.call(method,base+path,Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),body==null?null:http.encode(body),plan.timeoutSeconds(),plan.retryCount());
  }
  static String taskId(JsonNode node) { return taskId(node.isTextual()?node.textValue():""); }
  static String taskId(String id) {
    if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,255}")) throw ProviderHttp.uncertain();
    return id;
  }
}
