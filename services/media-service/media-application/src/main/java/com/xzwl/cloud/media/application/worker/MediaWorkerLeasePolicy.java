package com.xzwl.cloud.media.application.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed lease, heartbeat and polling parameters for media workers. */
public final class MediaWorkerLeasePolicy {

  private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(1);
  private static final Duration TICK_INTERVAL = Duration.ofSeconds(10);
  private static final int BATCH_SIZE = 100;

  public Duration leaseDuration() {
    return LEASE_DURATION;
  }

  public Duration heartbeatInterval() {
    return HEARTBEAT_INTERVAL;
  }

  public Duration tickInterval() {
    return TICK_INTERVAL;
  }

  public int batchSize() {
    return BATCH_SIZE;
  }

  public Instant leaseUntil(Instant claimedAt) {
    return Objects.requireNonNull(claimedAt, "claimedAt").plus(LEASE_DURATION);
  }

  public Instant heartbeatDueAt(Instant claimedAt) {
    return Objects.requireNonNull(claimedAt, "claimedAt").plus(HEARTBEAT_INTERVAL);
  }

  public boolean isLeaseExpired(Instant leaseUntil, Instant now) {
    Instant validatedLeaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    Instant validatedNow = Objects.requireNonNull(now, "now");
    return !validatedNow.isBefore(validatedLeaseUntil);
  }

  public boolean canClaim(Instant leaseUntil, Instant now) {
    Instant validatedNow = Objects.requireNonNull(now, "now");
    return leaseUntil == null || isLeaseExpired(leaseUntil, validatedNow);
  }
}
