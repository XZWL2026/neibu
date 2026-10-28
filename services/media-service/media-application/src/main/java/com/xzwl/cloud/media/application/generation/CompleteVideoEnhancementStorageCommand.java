package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for completing storage archival of a video enhancement output. */
public record CompleteVideoEnhancementStorageCommand(
    String jobId,
    String archivedOutputUrl,
    Instant completedAt) {}
