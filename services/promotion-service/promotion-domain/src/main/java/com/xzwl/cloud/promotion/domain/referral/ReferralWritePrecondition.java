package com.xzwl.cloud.promotion.domain.referral;

/**
 * Optimistic concurrency token for persisting one referral binding version.
 *
 * <p>The repository must, in one transaction, compare both the persisted aggregate version and
 * recommendation-graph revision with this token, append the version, and advance the graph
 * revision atomically. If either comparison fails, the caller must discard the current aggregate
 * instance, reload current facts and lineage, and resolve the benefits again.
 */
public record ReferralWritePrecondition(
    long expectedAggregateVersionNo,
    long expectedGraphRevision) {

  public ReferralWritePrecondition {
    if (expectedAggregateVersionNo < 0) {
      throw new IllegalArgumentException("expectedAggregateVersionNo must not be negative");
    }
    if (expectedGraphRevision < 0) {
      throw new IllegalArgumentException("expectedGraphRevision must not be negative");
    }
  }
}
