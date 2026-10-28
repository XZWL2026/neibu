package com.xzwl.cloud.routing.application.model;

public record ConfigureModelCommand(
    String modelId, String providerId, String providerModel, String configurationJson, boolean enabled) {
  public ConfigureModelCommand(String modelId,String providerId,String providerModel,String configurationJson) {
    this(modelId,providerId,providerModel,configurationJson,true);
  }
}
