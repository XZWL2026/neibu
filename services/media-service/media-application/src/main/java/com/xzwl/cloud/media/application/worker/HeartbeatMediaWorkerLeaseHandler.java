package com.xzwl.cloud.media.application.worker;

import java.util.Objects;

/** Renews an active lease only when the requesting worker still owns it. */
public final class HeartbeatMediaWorkerLeaseHandler {

  private final MediaWorkerLeaseStore store;
  private final MediaWorkerLeasePolicy policy;

  public HeartbeatMediaWorkerLeaseHandler(
      MediaWorkerLeaseStore store, MediaWorkerLeasePolicy policy) {
    this.store = Objects.requireNonNull(store, "store");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public MediaWorkerLease handle(HeartbeatMediaWorkerLeaseCommand command) {
    HeartbeatMediaWorkerLeaseCommand requested = Objects.requireNonNull(command, "command");
    String workId = requireNonBlank(requested.workId(), "workId");
    MediaWorkerLease current = store.load(workId);
    if (current == null) {
      throw new IllegalStateException("worker lease not found");
    }
    MediaWorkerLease renewed = current.heartbeat(
        requested.owner(), requested.heartbeatAt(), policy);
    if (renewed.equals(current)) {
      return current;
    }
    store.save(workId, renewed);
    return renewed;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
