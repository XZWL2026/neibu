package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for recording a failed media provider operation. */
public record FailMediaJobCommand(
    String jobId,
    String failureMessage,
    Instant failedAt) {}
