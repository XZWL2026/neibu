package com.xzwl.cloud.promotion.domain.referral;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class ReferralLineage {

  private final long graphRevision;
  private final String directInviterUserId;
  private final Set<String> ancestorUserIds;

  private ReferralLineage(
      long graphRevision,
      String directInviterUserId,
      Set<String> ancestorUserIds) {
    if (graphRevision < 0) {
      throw new IllegalArgumentException("graphRevision must not be negative");
    }
    this.graphRevision = graphRevision;
    this.directInviterUserId = requireNonBlank(directInviterUserId, "directInviterUserId");
    LinkedHashSet<String> validated = new LinkedHashSet<>();
    for (String ancestorUserId : Objects.requireNonNull(ancestorUserIds, "ancestorUserIds")) {
      String ancestor = requireNonBlank(ancestorUserId, "ancestorUserId");
      if (ancestor.equals(this.directInviterUserId)) {
        throw new IllegalArgumentException("direct inviter cannot also be an ancestor");
      }
      validated.add(ancestor);
    }
    this.ancestorUserIds = Set.copyOf(validated);
  }

  public static ReferralLineage atRevision(
      long graphRevision,
      String directInviterUserId,
      String... ancestorUserIds) {
    Objects.requireNonNull(ancestorUserIds, "ancestorUserIds");
    return new ReferralLineage(
        graphRevision,
        directInviterUserId,
        new LinkedHashSet<>(Arrays.asList(ancestorUserIds)));
  }

  static ReferralLineage of(String directInviterUserId, String... ancestorUserIds) {
    return atRevision(0, directInviterUserId, ancestorUserIds);
  }

  public long graphRevision() {
    return graphRevision;
  }

  public String directInviterUserId() {
    return directInviterUserId;
  }

  public Set<String> ancestorUserIds() {
    return Set.copyOf(ancestorUserIds);
  }

  boolean contains(String userId) {
    return directInviterUserId.equals(userId) || ancestorUserIds.contains(userId);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ReferralLineage that)) {
      return false;
    }
    return graphRevision == that.graphRevision
        && directInviterUserId.equals(that.directInviterUserId)
        && ancestorUserIds.equals(that.ancestorUserIds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(graphRevision, directInviterUserId, ancestorUserIds);
  }

  @Override
  public String toString() {
    return "ReferralLineage[graphRevision=%d, ancestorCount=%d]"
        .formatted(graphRevision, ancestorUserIds.size());
  }
}
