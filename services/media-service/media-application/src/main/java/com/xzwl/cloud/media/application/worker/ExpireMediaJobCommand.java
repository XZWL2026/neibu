package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

/** Application input for expiring an active media provider operation. */
public record ExpireMediaJobCommand(
    String jobId,
    MediaExecutionMode mode,
    Instant startedAt,
    Instant now) {}
