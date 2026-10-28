package com.xzwl.cloud.media.application.worker;

import java.time.Duration;
import java.time.Instant;

public final class MediaWorkerLeasePolicyTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant LEASE_UNTIL = Instant.parse("2026-09-04T07:03:00Z");
  private static final Instant HEARTBEAT_DUE = Instant.parse("2026-09-04T07:01:00Z");

  public static void main(String[] args) {
    exposesFixedSchedulingParameters();
    computesLeaseAndHeartbeatDeadlines();
    allowsClaimOnlyWhenLeaseExpired();
    supportsUnleasedWork();
    System.out.println("MediaWorkerLeasePolicyTest passed: 4");
  }

  private static void exposesFixedSchedulingParameters() {
    MediaWorkerLeasePolicy policy = new MediaWorkerLeasePolicy();

    assertEquals(Duration.ofMinutes(3), policy.leaseDuration(), "lease duration");
    assertEquals(Duration.ofMinutes(1), policy.heartbeatInterval(), "heartbeat interval");
    assertEquals(Duration.ofSeconds(10), policy.tickInterval(), "tick interval");
    assertEquals(100, policy.batchSize(), "batch size");
  }

  private static void computesLeaseAndHeartbeatDeadlines() {
    MediaWorkerLeasePolicy policy = new MediaWorkerLeasePolicy();

    assertEquals(LEASE_UNTIL, policy.leaseUntil(CLAIMED_AT), "lease until");
    assertEquals(HEARTBEAT_DUE, policy.heartbeatDueAt(CLAIMED_AT), "heartbeat due");
  }

  private static void allowsClaimOnlyWhenLeaseExpired() {
    MediaWorkerLeasePolicy policy = new MediaWorkerLeasePolicy();

    assertEquals(false, policy.isLeaseExpired(LEASE_UNTIL, LEASE_UNTIL.minusSeconds(1)), "before expiry");
    assertEquals(true, policy.isLeaseExpired(LEASE_UNTIL, LEASE_UNTIL), "at expiry");
    assertEquals(false, policy.canClaim(LEASE_UNTIL, LEASE_UNTIL.minusSeconds(1)), "claim before expiry");
    assertEquals(true, policy.canClaim(LEASE_UNTIL, LEASE_UNTIL), "claim at expiry");
  }

  private static void supportsUnleasedWork() {
    MediaWorkerLeasePolicy policy = new MediaWorkerLeasePolicy();

    assertEquals(true, policy.canClaim(null, CLAIMED_AT), "unleased claim");
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
