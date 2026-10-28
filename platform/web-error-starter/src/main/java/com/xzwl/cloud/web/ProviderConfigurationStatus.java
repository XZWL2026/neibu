package com.xzwl.cloud.web;
import java.util.List;
import java.util.Map;
/** Availability means this process has its required configuration, not a remote health probe. */
public record ProviderConfigurationStatus(String providerName, boolean configured, boolean available,
    String maskedIdentifier, List<String> missingEnvironmentVariables) {
  public static ProviderConfigurationStatus from(String name, Map<String,String> required, boolean implemented) {
    var missing=required.entrySet().stream().filter(e->e.getValue()==null || e.getValue().isBlank())
        .map(Map.Entry::getKey).sorted().toList();
    boolean configured=implemented && missing.isEmpty();
    return new ProviderConfigurationStatus(name,configured,configured,configured ? "***" : "",missing);
  }
}
