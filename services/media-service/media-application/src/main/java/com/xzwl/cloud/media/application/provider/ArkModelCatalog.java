package com.xzwl.cloud.media.application.provider;

import java.util.List;
import java.util.Map;

/** Read-only upstream catalog; listing does not prove model invocation permissions. */
public interface ArkModelCatalog {
  Result list(String providerId);
  record Model(String id, String name, String type, String status, Capability capability) {}
  record Capability(List<String> resolutions, List<String> aspectRatios, int maxCount,
      int minDurationSeconds, int maxDurationSeconds, Map<String,Map<String,String>> sizes) {}
  record Result(long version, String providerId, boolean success, List<Model> models, String message) {}
}
