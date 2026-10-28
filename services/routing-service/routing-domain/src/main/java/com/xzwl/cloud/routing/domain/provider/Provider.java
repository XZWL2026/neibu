package com.xzwl.cloud.routing.domain.provider;

import java.util.Objects;

/** Provider metadata managed by operations without carrying provider credentials. */
public final class Provider {

  private final String id;
  private final String displayName;
  private final String endpoint;
  private final boolean enabled;
  private final String configurationJson;

  private Provider(
      String id, String displayName, String endpoint, boolean enabled, String configurationJson) {
    this.id = requireNonBlank(id, "id");
    this.displayName = requireNonBlank(displayName, "displayName");
    this.endpoint = requireNonBlank(endpoint, "endpoint");
    this.enabled = enabled;
    this.configurationJson = requireNonBlank(configurationJson, "configurationJson");
  }

  public static Provider configure(
      String id, String displayName, String endpoint, boolean enabled, String configurationJson) {
    return new Provider(id, displayName, endpoint, enabled, configurationJson);
  }

  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  public String endpoint() {
    return endpoint;
  }

  public boolean enabled() {
    return enabled;
  }

  public String configurationJson() {
    return configurationJson;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
