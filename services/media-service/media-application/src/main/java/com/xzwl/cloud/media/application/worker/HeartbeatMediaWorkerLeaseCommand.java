package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

/** Application input for renewing ownership of a unit of media worker work. */
public record HeartbeatMediaWorkerLeaseCommand(String workId, String owner, Instant heartbeatAt) {}
