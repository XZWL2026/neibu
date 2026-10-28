package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.*;
import java.util.*;

/** Ordinary material review through the configured Doubao bridge. */
final class MaasMediaAdapter {
  private final ProviderHttp http;
  MaasMediaAdapter(ProviderHttp http) { this.http=http; }
  static boolean matches(String base) {
    return ProviderSettings.providerEndpoint(base).getRawPath().replaceAll("/+$","").equals("/doubao/api/v3");
  }
  static boolean supports(String model) {
    return Set.of("doubao-seedance-2-5-260628","doubao-seedance-2-0-260128",
        "doubao-seedance-2-0-fast-260128","doubao-seedance-2-0-mini-260615").contains(model);
  }
  static String origin(String base) {
    var uri=ProviderSettings.providerEndpoint(base);return uri.getScheme()+"://"+uri.getRawAuthority();
  }
  JsonNode asset(String base,String key,String model,String action,Map<String,String> payload) {
    if(key.isBlank() || !supports(model)) throw new DefinitiveRejectionException("PORTRAIT_PROVIDER_UNSUPPORTED");
    var body=new LinkedHashMap<>(payload);body.put("model",model);
    String path=switch(action) {
      case "CreateAssetGroup" -> "/v1/private-avatar/groups";
      case "CreateAsset" -> "/v1/private-avatar/assets";
      case "GetAsset" -> "/v1/private-avatar/assets/"+ArkMediaAdapter.taskId(payload.get("Id"));
      default -> throw new IllegalArgumentException("unsupported asset action");
    };
    boolean read=action.equals("GetAsset");
    if(read) path+="?ProjectName="+java.net.URLEncoder.encode(payload.getOrDefault("ProjectName","default"),java.nio.charset.StandardCharsets.UTF_8);
    var result=http.call(read?"GET":"POST",origin(base)+path,
        Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),read?null:http.encode(body));
    if(result.path("ResponseMetadata").hasNonNull("Error") || result.hasNonNull("error"))
      throw new DefinitiveRejectionException("PORTRAIT_REVIEW_FAILED");
    if(result.path("Result").isObject()) return result.path("Result");
    if(read && result.hasNonNull("Id")) return result;
    throw ProviderHttp.uncertain();
  }
}
