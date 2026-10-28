package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for recording provider acceptance of a media job. */
public record AcceptMediaJobCommand(String jobId, String providerTaskId, Instant acceptedAt) {}
