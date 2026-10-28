package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for applying a completed provider image output. */
public record ApplyImageProviderCompletionCommand(
    String jobId,
    String providerOutputUrl,
    Instant completedAt) {}
