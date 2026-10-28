package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

/** Application input for claiming a unit of media worker work. */
public record ClaimMediaWorkerLeaseCommand(String workId, String owner, Instant claimedAt) {}
