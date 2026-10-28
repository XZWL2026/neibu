package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

public final class ClaimMediaWorkerLeaseHandlerTest {

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant BEFORE_EXPIRY = Instant.parse("2026-09-04T07:02:59Z");
  private static final Instant EXPIRED_AT = Instant.parse("2026-09-04T07:03:00Z");

  public static void main(String[] args) {
    claimsUnleasedWork();
    leavesActiveLeaseWithOriginalOwner();
    takesOverExpiredLease();
    replaysActiveLeaseForSameOwnerWithoutSaving();
    System.out.println("ClaimMediaWorkerLeaseHandlerTest passed: 4");
  }

  private static void claimsUnleasedWork() {
    RecordingStore store = new RecordingStore(null);

    MediaWorkerLease lease = handler(store).handle(
        new ClaimMediaWorkerLeaseCommand("job-1", "worker-a", CLAIMED_AT));

    assertEquals("worker-a", lease.owner(), "new owner");
    assertEquals(CLAIMED_AT.plusSeconds(180), lease.leaseUntil(), "new lease until");
    assertEquals(1, store.saveCount, "new save count");
  }

  private static void leavesActiveLeaseWithOriginalOwner() {
    MediaWorkerLease existing = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    RecordingStore store = new RecordingStore(existing);

    MediaWorkerLease result = handler(store).handle(
        new ClaimMediaWorkerLeaseCommand("job-1", "worker-b", BEFORE_EXPIRY));

    assertEquals(existing, result, "active lease");
    assertEquals(0, store.saveCount, "active save count");
  }

  private static void takesOverExpiredLease() {
    MediaWorkerLease existing = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    RecordingStore store = new RecordingStore(existing);

    MediaWorkerLease result = handler(store).handle(
        new ClaimMediaWorkerLeaseCommand("job-1", "worker-b", EXPIRED_AT));

    assertEquals("worker-b", result.owner(), "takeover owner");
    assertEquals(EXPIRED_AT, result.claimedAt(), "takeover claimedAt");
    assertEquals(1, store.saveCount, "takeover save count");
  }

  private static void replaysActiveLeaseForSameOwnerWithoutSaving() {
    MediaWorkerLease existing = MediaWorkerLease.claim("worker-a", CLAIMED_AT, policy());
    RecordingStore store = new RecordingStore(existing);

    MediaWorkerLease result = handler(store).handle(
        new ClaimMediaWorkerLeaseCommand("job-1", "worker-a", BEFORE_EXPIRY));

    assertEquals(existing, result, "same owner lease");
    assertEquals(0, store.saveCount, "same owner save count");
  }

  private static ClaimMediaWorkerLeaseHandler handler(RecordingStore store) {
    return new ClaimMediaWorkerLeaseHandler(store, policy());
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
