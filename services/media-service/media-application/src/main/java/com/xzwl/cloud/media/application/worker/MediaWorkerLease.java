package com.xzwl.cloud.media.application.worker;

import java.time.Instant;
import java.util.Objects;

/** Immutable ownership snapshot for one worker claim. */
public record MediaWorkerLease(String owner, Instant claimedAt, Instant leaseUntil) {

  public MediaWorkerLease {
    owner = requireOwner(owner);
    claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (leaseUntil.isBefore(claimedAt)) {
      throw new IllegalArgumentException("leaseUntil must not be before claimedAt");
    }
  }

  public static MediaWorkerLease claim(
      String owner, Instant claimedAt, MediaWorkerLeasePolicy policy) {
    MediaWorkerLeasePolicy validatedPolicy = Objects.requireNonNull(policy, "policy");
    Instant validatedClaimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    return new MediaWorkerLease(owner, validatedClaimedAt, validatedPolicy.leaseUntil(validatedClaimedAt));
  }

  public MediaWorkerLease heartbeat(
      String requestingOwner, Instant heartbeatAt, MediaWorkerLeasePolicy policy) {
    requireCurrentOwner(requestingOwner);
    Instant validatedHeartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
    if (validatedHeartbeatAt.isBefore(claimedAt)) {
      throw new IllegalArgumentException("heartbeatAt must not be before claimedAt");
    }
    if (canBeClaimedAt(validatedHeartbeatAt)) {
      throw new IllegalStateException("expired lease cannot heartbeat");
    }
    return claim(owner, validatedHeartbeatAt, policy);
  }

  public MediaWorkerLease takeOver(
      String newOwner, Instant takeoverAt, MediaWorkerLeasePolicy policy) {
    Instant validatedTakeoverAt = Objects.requireNonNull(takeoverAt, "takeoverAt");
    if (!canBeClaimedAt(validatedTakeoverAt)) {
      throw new IllegalStateException("active lease cannot be taken over");
    }
    return claim(newOwner, validatedTakeoverAt, policy);
  }

  public boolean canBeClaimedAt(Instant now) {
    return Objects.requireNonNull(now, "now").compareTo(leaseUntil) >= 0;
  }

  private void requireCurrentOwner(String requestingOwner) {
    if (!owner.equals(requireOwner(requestingOwner))) {
      throw new IllegalStateException("only the current lease owner can heartbeat");
    }
  }

  private static String requireOwner(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("owner must not be blank");
    }
    return value;
  }
}
