package com.xzwl.cloud.routing.domain.route;

import java.time.Instant;
import java.util.Objects;

public record RouteSnapshot(
    String taskId,
    String requestedModel,
    String providerId,
    String providerDisplayName,
    String providerEndpoint,
    String providerModel,
    String providerConfigurationJson,
    String modelConfigurationJson,
    Instant selectedAt) {

  public RouteSnapshot {
    taskId = requireNonBlank(taskId, "taskId");
    requestedModel = requireNonBlank(requestedModel, "requestedModel");
    providerId = requireNonBlank(providerId, "providerId");
    providerDisplayName = requireNonBlank(providerDisplayName, "providerDisplayName");
    providerEndpoint = requireNonBlank(providerEndpoint, "providerEndpoint");
    providerModel = requireNonBlank(providerModel, "providerModel");
    providerConfigurationJson = requireNonBlank(
        providerConfigurationJson, "providerConfigurationJson");
    modelConfigurationJson = requireNonBlank(modelConfigurationJson, "modelConfigurationJson");
    selectedAt = Objects.requireNonNull(selectedAt, "selectedAt");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
