package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.*;
import com.xzwl.cloud.media.application.task.*;
import java.util.*;

public final class HttpArkModelCatalog implements ArkModelCatalog {
  private final ArkConnectionStore store;
  private final ProviderHttp http;
  public HttpArkModelCatalog(ArkConnectionStore store, ProviderHttp http) { this.store=store; this.http=http; }

  @Override public Result list(String providerId) {
    ArkConnectionStore.Connection connection;
    try { connection=store.load(providerId); }
    catch(ArkConnectionStore.NotEditable unavailable) { return new Result(0,"",false,List.of(),"连接配置暂不可读取，请重新加载。"); }
    return list(connection);
  }

  Result list(ArkConnectionStore.Connection connection) {
    if(connection.apiKey()==null || connection.apiKey().isBlank() || connection.baseUrl()==null || connection.baseUrl().isBlank())
      return failed(connection,"请先保存完整的供应商连接与 API Key。");
    try {
      boolean newApi=ProviderSettings.newApiDoubao(connection.baseUrl());
      var base=ProviderSettings.providerEndpoint(connection.baseUrl());
      var response=http.call("GET",newApi ? base.resolve("/v1/models").toString() : base+"/models",
          Map.of("Authorization","Bearer "+connection.apiKey()),null);
      var data=response.path("data");
      if(response.hasNonNull("error") || response.has("success") && !response.path("success").asBoolean() || !data.isArray()) return failed(connection,"模型目录格式无效，请稍后重试。");
      var models=new ArrayList<Model>();
      for(var item:data) {
        if(newApi) {
          String id=item.path("id").asText("");
          if(id.isBlank() || id.length()>128) return failed(connection,"模型目录格式无效，请稍后重试。");
          var capability=ArkModelCapabilities.forModel(id,"VIDEO");
          models.add(new Model(id,item.path("name").asText(id),capability==null ? "UNSUPPORTED" : "VIDEO",
              item.path("status").asText(""),capability));
          continue;
        }
        if(ProviderSettings.zex(connection.baseUrl())) {
          String id=item.path("id").asText("");
          if(id.isBlank() || id.length()>128) return failed(connection,"模型目录格式无效，请稍后重试。");
          var capability=ZexMediaAdapter.capability(id);
          String type=capability==null ? "UNSUPPORTED" : capability.minDurationSeconds()>0 ? "VIDEO" : "IMAGE";
          models.add(new Model(id,item.path("name").asText(id),type,ZexMediaAdapter.synchronousOnly(id) ? "SynchronousOnly" : item.path("status").asText(""),capability));
          continue;
        }
        for(var output:item.path("modalities").path("output_modalities")) {
          String type=switch(output.asText()) { case "image" -> "IMAGE"; case "video" -> "VIDEO"; default -> ""; };
          if(type.isEmpty()) continue;
          String id=item.path("id").asText("");
          if(id.isBlank() || id.length()>128) return failed(connection,"模型目录格式无效，请稍后重试。");
          models.add(new Model(id,item.path("name").asText(id),type,item.path("status").asText(""),ArkModelCapabilities.forModel(id,type)));
        }
      }
      models.sort(Comparator.comparing(Model::type).thenComparing(Model::id));
      return new Result(connection.version(),connection.providerId(),true,List.copyOf(models),"");
    } catch(DefinitiveRejectionException denied) {
      return failed(connection,"供应商拒绝查询模型，请检查已保存的 API Key、连接地址和查询权限。");
    } catch(DependencyUnavailableException | IllegalArgumentException unavailable) {
      return failed(connection,"模型目录读取失败，请检查供应商连接后重试。");
    }
  }
  private Result failed(ArkConnectionStore.Connection connection,String message) {
    return new Result(connection.version(),connection.providerId(),false,List.of(),message);
  }
}
