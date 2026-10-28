package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

/** Application input for deciding whether an asynchronous video should be polled. */
public record PlanVideoProviderPollCommand(
    Instant startedAt,
    Instant lastPolledAt,
    Instant now) {}
