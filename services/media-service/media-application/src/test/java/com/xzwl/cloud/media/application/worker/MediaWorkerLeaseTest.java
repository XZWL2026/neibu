package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

public final class MediaWorkerLeaseTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant HEARTBEAT_AT = Instant.parse("2026-09-04T07:00:30Z");
  private static final Instant EXPIRED_AT = Instant.parse("2026-09-04T07:03:00Z");

  public static void main(String[] args) {
    claimsWithPolicyLeaseWindow();
    heartbeatsOnlyForCurrentOwnerBeforeExpiry();
    rejectsHeartbeatAfterExpiry();
    allowsTakeoverOnlyAfterExpiry();
    System.out.println("MediaWorkerLeaseTest passed: 4");
  }

  private static void claimsWithPolicyLeaseWindow() {
    MediaWorkerLease lease = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());

    assertEquals("worker-a", lease.owner(), "owner");
    assertEquals(CLAIMED_AT, lease.claimedAt(), "claimedAt");
    assertEquals(EXPIRED_AT, lease.leaseUntil(), "leaseUntil");
  }

  private static void heartbeatsOnlyForCurrentOwnerBeforeExpiry() {
    MediaWorkerLease lease = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    MediaWorkerLease renewed = lease.heartbeat("worker-a", HEARTBEAT_AT, policy());

    assertEquals(HEARTBEAT_AT, renewed.claimedAt(), "heartbeat claimedAt");
    assertEquals(HEARTBEAT_AT.plusSeconds(180), renewed.leaseUntil(), "heartbeat leaseUntil");
    assertEquals(false, renewed.canBeClaimedAt(HEARTBEAT_AT.plusSeconds(1)), "renewed claimability");

    try {
      lease.heartbeat("worker-b", HEARTBEAT_AT, policy());
      throw new AssertionError("different worker should not heartbeat lease");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void rejectsHeartbeatAfterExpiry() {
    MediaWorkerLease lease = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    try {
      lease.heartbeat("worker-a", EXPIRED_AT, policy());
      throw new AssertionError("expired lease should not heartbeat");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void allowsTakeoverOnlyAfterExpiry() {
    MediaWorkerLease lease = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    try {
      lease.takeOver("worker-b", EXPIRED_AT.minusSeconds(1), policy());
      throw new AssertionError("active lease should not be taken over");
    } catch (IllegalStateException expected) {
      // expected
    }

    MediaWorkerLease takeover = lease.takeOver("worker-b", EXPIRED_AT, policy());
    assertEquals("worker-b", takeover.owner(), "takeover owner");
    assertEquals(EXPIRED_AT, takeover.claimedAt(), "takeover claimedAt");
  }

  private static MediaWorkerLeasePolicy policy() {
    return new MediaWorkerLeasePolicy();
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
