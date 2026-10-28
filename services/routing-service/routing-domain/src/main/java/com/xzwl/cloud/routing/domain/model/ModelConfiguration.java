package com.xzwl.cloud.routing.domain.model;

public record ModelConfiguration(
    String modelId, String providerId, String providerModel, String configurationJson, boolean enabled) {

  public ModelConfiguration(String modelId,String providerId,String providerModel,String configurationJson) {
    this(modelId,providerId,providerModel,configurationJson,true);
  }

  public ModelConfiguration {
    modelId = requireNonBlank(modelId, "modelId");
    providerId = requireNonBlank(providerId, "providerId");
    providerModel = requireNonBlank(providerModel, "providerModel");
    configurationJson = requireNonBlank(configurationJson, "configurationJson");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
