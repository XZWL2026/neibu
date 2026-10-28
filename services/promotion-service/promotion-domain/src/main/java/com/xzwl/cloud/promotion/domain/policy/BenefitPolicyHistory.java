package com.xzwl.cloud.promotion.domain.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Immutable history of strictly ordered benefit policy versions. */
public final class BenefitPolicyHistory {

  private final String historyId;
  private final List<BenefitPolicyVersion> versions;

  private BenefitPolicyHistory(List<BenefitPolicyVersion> privateSnapshot) {
    validate(privateSnapshot);
    this.historyId = privateSnapshot.getFirst().historyId();
    this.versions = privateSnapshot;
  }

  public static BenefitPolicyHistory rehydrate(List<BenefitPolicyVersion> versions) {
    if (versions == null) {
      throw new IllegalArgumentException("policy versions are required");
    }
    return new BenefitPolicyHistory(immutableSnapshot(versions));
  }

  public String historyId() {
    return historyId;
  }

  public List<BenefitPolicyVersion> versions() {
    return versions;
  }

  public Optional<BenefitPolicyVersion> effectiveAt(Instant orderCreatedAt) {
    if (orderCreatedAt == null) {
      throw new IllegalArgumentException("order creation time is required");
    }
    int low = 0;
    int high = versions.size() - 1;
    int selected = -1;
    while (low <= high) {
      int middle = low + ((high - low) / 2);
      BenefitPolicyVersion candidate = versions.get(middle);
      if (candidate.effectiveFrom().isAfter(orderCreatedAt)) {
        high = middle - 1;
      } else {
        selected = middle;
        low = middle + 1;
      }
    }
    return selected < 0 ? Optional.empty() : Optional.of(versions.get(selected));
  }

  public BenefitPolicyHistory append(BenefitPolicyVersion nextVersion) {
    if (nextVersion == null) {
      throw new IllegalArgumentException("next policy version is required");
    }
    BenefitPolicyVersion current = versions.getLast();
    if (!historyId.equals(nextVersion.historyId())) {
      throw new IllegalArgumentException("policy version belongs to a different history");
    }
    if (nextVersion.versionNo() != current.versionNo() + 1) {
      throw new IllegalArgumentException("policy version number must be strictly continuous");
    }
    if (!nextVersion.effectiveFrom().isAfter(current.effectiveFrom())) {
      throw new IllegalArgumentException("policy effective time must be strictly increasing");
    }
    List<BenefitPolicyVersion> appended = new ArrayList<>(versions);
    appended.add(nextVersion);
    return new BenefitPolicyHistory(immutableSnapshot(appended));
  }

  private static List<BenefitPolicyVersion> immutableSnapshot(
      List<BenefitPolicyVersion> source) {
    return Collections.unmodifiableList(new ArrayList<>(source));
  }

  private static void validate(List<BenefitPolicyVersion> source) {
    if (source.isEmpty()) {
      throw new IllegalArgumentException("policy history must contain at least one version");
    }
    BenefitPolicyVersion first = source.getFirst();
    if (first == null) {
      throw new IllegalArgumentException("policy version is required");
    }
    if (first.versionNo() != 1) {
      throw new IllegalArgumentException("policy history must start at version one");
    }
    for (int index = 1; index < source.size(); index++) {
      BenefitPolicyVersion previous = source.get(index - 1);
      BenefitPolicyVersion current = source.get(index);
      if (current == null) {
        throw new IllegalArgumentException("policy version is required");
      }
      if (!first.historyId().equals(current.historyId())) {
        throw new IllegalArgumentException("all policy versions must belong to one history");
      }
      if (current.versionNo() != previous.versionNo() + 1) {
        throw new IllegalArgumentException("policy version numbers must be strictly continuous");
      }
      if (!current.effectiveFrom().isAfter(previous.effectiveFrom())) {
        throw new IllegalArgumentException("policy effective times must be strictly increasing");
      }
    }
  }

  @Override
  public String toString() {
    return "BenefitPolicyHistory{"
        + "historyId='"
        + historyId
        + '\''
        + ", versionCount="
        + versions.size()
        + '}';
  }
}
