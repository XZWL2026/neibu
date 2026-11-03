package com.xzwl.cloud.media.infrastructure.provider;

import java.net.URI;
import java.util.Map;

/** LetsLLM's documented aliases, bound to the application's reviewed version profiles. */
final class LetsLlmModels {
  private static final Map<String,String> MODELS=Map.of(
      "doubao-seedance-2-5-260628","doubao-seedance-2-5",
      "doubao-seedance-2-0-260128","doubao-seedance-2-0",
      "doubao-seedance-2-0-fast-260128","doubao-seedance-2-0-fast",
      "doubao-seedance-2-0-mini-260615","doubao-seedance-2-0-mini",
      "doubao-seedance-1-5-pro-251215","doubao-seedance-1-5-pro",
      "doubao-seedance-1-0-pro-250528","doubao-seedance-1-0-pro");

  static boolean matches(String base) {
    return "api.letsllm.com".equalsIgnoreCase(URI.create(base).getHost());
  }
  static String canonical(String model) {
    return MODELS.entrySet().stream().filter(e->e.getValue().equals(model)).map(Map.Entry::getKey).findFirst().orElse(model);
  }
  static String requestModel(String base,String model) {
    return matches(base)?MODELS.getOrDefault(model,model):model;
  }
}
