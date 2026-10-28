package com.xzwl.cloud.media.application.generation;

/** Application input for normalizing a provider polling response. */
public record ClassifyMediaProviderPollCommand(
    String providerStatus,
    String outputUrl,
    String errorMessage) {}
