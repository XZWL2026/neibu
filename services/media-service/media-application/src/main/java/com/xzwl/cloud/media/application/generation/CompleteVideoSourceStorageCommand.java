package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for completing source archival before video enhancement. */
public record CompleteVideoSourceStorageCommand(
    String jobId,
    String archivedSourceUrl,
    Instant queuedAt) {}
