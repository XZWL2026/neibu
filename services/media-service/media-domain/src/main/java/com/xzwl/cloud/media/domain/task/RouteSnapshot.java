package com.xzwl.cloud.media.domain.task;

import java.time.Instant;
import java.net.URI;
import java.util.Objects;

/** Matches Routing's new-system route response. Secrets are never part of this snapshot. */
public record RouteSnapshot(String taskId, String requestedModel, String providerId,
    String providerDisplayName, String providerEndpoint, String providerModel,
    String providerConfigurationJson, String modelConfigurationJson, Instant selectedAt) {
  public RouteSnapshot {
    MediaReference.identifier(taskId,"taskId");
    MediaReference.identifier(requestedModel,"requestedModel");
    MediaReference.identifier(providerId,"providerId");
    MediaReference.text(providerDisplayName,"providerDisplayName",256);
    providerUri(providerEndpoint);
    MediaReference.text(providerModel,"providerModel",256);
    MediaReference.text(providerConfigurationJson,"providerConfigurationJson",32000);
    MediaReference.text(modelConfigurationJson,"modelConfigurationJson",32000);
    Objects.requireNonNull(selectedAt);
  }
  public static URI providerUri(String value) {
    MediaReference.text(value,"providerEndpoint",8192);
    URI uri=URI.create(value);
    if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
        || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException("Invalid provider endpoint");
    return uri;
  }
}
