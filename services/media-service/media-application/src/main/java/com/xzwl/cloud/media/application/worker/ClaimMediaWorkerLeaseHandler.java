package com.xzwl.cloud.media.application.worker;

import java.util.Objects;

/** Claims unleased work or takes over a lease that has reached its expiry. */
public final class ClaimMediaWorkerLeaseHandler {

  private final MediaWorkerLeaseStore store;
  private final MediaWorkerLeasePolicy policy;

  public ClaimMediaWorkerLeaseHandler(
      MediaWorkerLeaseStore store, MediaWorkerLeasePolicy policy) {
    this.store = Objects.requireNonNull(store, "store");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public MediaWorkerLease handle(ClaimMediaWorkerLeaseCommand command) {
    ClaimMediaWorkerLeaseCommand requested = Objects.requireNonNull(command, "command");
    String workId = requireNonBlank(requested.workId(), "workId");
    MediaWorkerLease current = store.load(workId);
    MediaWorkerLease claimed;
    if (current == null) {
      claimed = MediaWorkerLease.claim(requested.owner(), requested.claimedAt(), policy);
    } else if (!policy.canClaim(current.leaseUntil(), requested.claimedAt())) {
      return current;
    } else {
      claimed = current.takeOver(requested.owner(), requested.claimedAt(), policy);
    }
    store.save(workId, claimed);
    return claimed;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
