package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for finalizing a video enhancement output. */
public record CompleteVideoEnhancementCommand(
    String jobId,
    String finalOutputUrl,
    Instant completedAt) {}
