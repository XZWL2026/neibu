package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.*;
import java.util.Map;
import java.util.Set;

/** Account-scoped Ark asset import; POSTs are never retried after an uncertain receipt. */
final class LetsLlmAssets {
  private final ProviderHttp http;
  LetsLlmAssets(ProviderHttp http) { this.http=http; }
  JsonNode call(String base,String key,String action,Map<String,String> payload) {
    if(!LetsLlmModels.matches(base) || key.isBlank()
        || !Set.of("CreateAssetGroup","CreateAsset","GetAsset").contains(action))
      throw new DefinitiveRejectionException("PORTRAIT_PROVIDER_UNSUPPORTED");
    var endpoint=ProviderSettings.providerEndpoint(base).resolve("/?Action="+action+"&Version=2024-01-01");
    var result=http.call("POST",endpoint.toString(),Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),http.encode(payload));
    if(result.hasNonNull("error") || result.path("ResponseMetadata").hasNonNull("Error"))
      throw new DefinitiveRejectionException("PORTRAIT_REVIEW_FAILED");
    var asset=result.path("Result").isObject()?result.path("Result"):result;
    if(!asset.hasNonNull("Id")) throw ProviderHttp.uncertain();
    return asset;
  }
}
