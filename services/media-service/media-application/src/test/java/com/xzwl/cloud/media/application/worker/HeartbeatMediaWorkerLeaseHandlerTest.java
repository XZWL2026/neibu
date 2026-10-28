package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

public final class HeartbeatMediaWorkerLeaseHandlerTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant HEARTBEAT_AT = Instant.parse("2026-09-04T07:00:30Z");
  private static final Instant EXPIRED_AT = Instant.parse("2026-09-04T07:03:00Z");

  public static void main(String[] args) {
    renewsLeaseForCurrentOwner();
    rejectsHeartbeatFromDifferentOwner();
    rejectsHeartbeatAfterExpiry();
    rejectsMissingLease();
    replaysHeartbeatAtSameTimestampWithoutSaving();
    System.out.println("HeartbeatMediaWorkerLeaseHandlerTest passed: 5");
  }

  private static void renewsLeaseForCurrentOwner() {
    MediaWorkerLease existing = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    RecordingStore store = new RecordingStore(existing);

    MediaWorkerLease renewed = handler(store).handle(
        new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-a", HEARTBEAT_AT));

    assertEquals(HEARTBEAT_AT, renewed.claimedAt(), "renewed claimedAt");
    assertEquals(HEARTBEAT_AT.plusSeconds(180), renewed.leaseUntil(), "renewed leaseUntil");
    assertEquals(1, store.saveCount, "renewed save count");
  }

  private static void rejectsHeartbeatFromDifferentOwner() {
    RecordingStore store = new RecordingStore(
        MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy()));

    try {
      handler(store).handle(
          new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-b", HEARTBEAT_AT));
      throw new AssertionError("different owner should not heartbeat lease");
    } catch (IllegalStateException expected) {
      // expected
    }
    assertEquals(0, store.saveCount, "different owner save count");
  }

  private static void rejectsHeartbeatAfterExpiry() {
    RecordingStore store = new RecordingStore(
        MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy()));

    try {
      handler(store).handle(
          new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-a", EXPIRED_AT));
      throw new AssertionError("expired lease should not heartbeat");
    } catch (IllegalStateException expected) {
      // expected
    }
    assertEquals(0, store.saveCount, "expired save count");
  }

  private static void rejectsMissingLease() {
    try {
      handler(new RecordingStore(null)).handle(
          new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-a", HEARTBEAT_AT));
      throw new AssertionError("missing lease should be rejected");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void replaysHeartbeatAtSameTimestampWithoutSaving() {
    MediaWorkerLease existing = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    RecordingStore store = new RecordingStore(existing);
    HeartbeatMediaWorkerLeaseHandler handler = handler(store);
    MediaWorkerLease renewed = handler.handle(
        new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-a", HEARTBEAT_AT));

    MediaWorkerLease repeated = handler.handle(
        new HeartbeatMediaWorkerLeaseCommand("job-1", "worker-a", HEARTBEAT_AT));

    assertEquals(renewed, repeated, "replayed lease");
    assertEquals(1, store.saveCount, "replayed save count");
  }

  private static HeartbeatMediaWorkerLeaseHandler handler(RecordingStore store) {
    return new HeartbeatMediaWorkerLeaseHandler(store, policy());
  }

  private static MediaWorkerLeasePolicy policy() {
    return new MediaWorkerLeasePolicy();
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static final class RecordingStore implements MediaWorkerLeaseStore {

    private MediaWorkerLease lease;
    private int saveCount;

    private RecordingStore(MediaWorkerLease lease) {
      this.lease = lease;
    }

    @Override
    public MediaWorkerLease load(String workId) {
      assertEquals("job-1", workId, "work id");
      return lease;
    }

    @Override
    public void save(String workId, MediaWorkerLease lease) {
      assertEquals("job-1", workId, "save work id");
      this.lease = lease;
      saveCount++;
    }
  }
}
