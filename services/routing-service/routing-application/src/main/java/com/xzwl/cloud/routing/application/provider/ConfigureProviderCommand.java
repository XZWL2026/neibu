package com.xzwl.cloud.routing.application.provider;

/** Application input for an administrator-managed provider configuration. */
public record ConfigureProviderCommand(
    String providerId,
    String displayName,
    String endpoint,
    boolean enabled,
    String configurationJson) {}
