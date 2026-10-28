package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for completing storage archival of a provider output. */
public record CompleteMediaStorageCommand(
    String jobId,
    String archivedOutputUrl,
    Instant completedAt) {}
