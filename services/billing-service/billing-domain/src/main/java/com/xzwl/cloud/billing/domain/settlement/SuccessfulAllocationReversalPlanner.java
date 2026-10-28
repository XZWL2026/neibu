package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SuccessfulAllocationReversalPlanner {

  private SuccessfulAllocationReversalPlanner() {}

  public static List<SuccessfulAllocationReversalFact> plan(
      List<SuccessfulAllocationFact> successfulAllocationFacts,
      List<SuccessfulAllocationReversalFact> existingReversalFacts,
      List<SuccessfulAllocationReversalRequest> requests) {
    List<SuccessfulAllocationFact> originals =
        List.copyOf(
            Objects.requireNonNull(successfulAllocationFacts, "successfulAllocationFacts"));
    List<SuccessfulAllocationReversalFact> existing =
        List.copyOf(Objects.requireNonNull(existingReversalFacts, "existingReversalFacts"));
    List<SuccessfulAllocationReversalRequest> requested =
        List.copyOf(Objects.requireNonNull(requests, "requests"));
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("requests must not be empty");
    }

    Map<AllocationKey, SuccessfulAllocationFact> originalsByKey = indexOriginals(originals);
    Map<String, SuccessfulAllocationReversalFact> reversalsById = new HashMap<>();
    Map<AllocationKey, Decimal4> reversedByAllocation = new HashMap<>();
    indexExisting(existing, originalsByKey, reversalsById, reversedByAllocation);

    Set<String> requestedReversalIds = new HashSet<>();
    List<SuccessfulAllocationReversalFact> newReversals = new ArrayList<>();
    for (SuccessfulAllocationReversalRequest request : requested) {
      if (!requestedReversalIds.add(request.reversalId())) {
        throw new IllegalArgumentException("duplicate reversal request id");
      }
      AllocationKey key = new AllocationKey(request.settlementId(), request.allocationId());
      SuccessfulAllocationFact original = originalsByKey.get(key);
      if (original == null) {
        throw new IllegalArgumentException("reversal request references an unknown successful allocation");
      }

      SuccessfulAllocationReversalFact prior = reversalsById.get(request.reversalId());
      if (prior != null) {
        if (sameReversal(prior, request)) {
          continue;
        }
        throw new IllegalArgumentException("reversal id conflicts with an existing reversal");
      }

      Decimal4 total =
          reversedByAllocation
              .getOrDefault(key, Decimal4.zero())
              .add(request.reversedPoints4());
      if (total.compareTo(original.successfulPoints4()) > 0) {
        throw new IllegalArgumentException("cumulative reversal exceeds original successful points");
      }
      reversedByAllocation.put(key, total);
      newReversals.add(from(request.reversalId(), request.reversedPoints4(), original));
    }
    return List.copyOf(newReversals);
  }

  private static Map<AllocationKey, SuccessfulAllocationFact> indexOriginals(
      List<SuccessfulAllocationFact> originals) {
    Map<AllocationKey, SuccessfulAllocationFact> indexed = new HashMap<>();
    for (SuccessfulAllocationFact original : originals) {
      AllocationKey key = new AllocationKey(original.settlementId(), original.allocationId());
      if (indexed.put(key, original) != null) {
        throw new IllegalArgumentException("duplicate successful allocation key");
      }
    }
    return indexed;
  }

  private static void indexExisting(
      List<SuccessfulAllocationReversalFact> existing,
      Map<AllocationKey, SuccessfulAllocationFact> originalsByKey,
      Map<String, SuccessfulAllocationReversalFact> reversalsById,
      Map<AllocationKey, Decimal4> reversedByAllocation) {
    for (SuccessfulAllocationReversalFact reversal : existing) {
      if (reversalsById.put(reversal.reversalId(), reversal) != null) {
        throw new IllegalArgumentException("duplicate existing reversal id");
      }
      AllocationKey key = new AllocationKey(reversal.settlementId(), reversal.allocationId());
      SuccessfulAllocationFact original = originalsByKey.get(key);
      if (original == null) {
        throw new IllegalArgumentException("existing reversal references an unknown successful allocation");
      }
      requireFrozenMetadata(reversal, original);
      Decimal4 total =
          reversedByAllocation
              .getOrDefault(key, Decimal4.zero())
              .add(reversal.reversedPoints4());
      if (total.compareTo(original.successfulPoints4()) > 0) {
        throw new IllegalArgumentException("cumulative reversal exceeds original successful points");
      }
      reversedByAllocation.put(key, total);
    }
  }

  private static SuccessfulAllocationReversalFact from(
      String reversalId, Decimal4 reversedPoints4, SuccessfulAllocationFact original) {
    return new SuccessfulAllocationReversalFact(
        reversalId,
        original.settlementId(),
        original.taskId(),
        original.allocationId(),
        original.lotId(),
        original.walletId(),
        original.lotType(),
        original.benefitEligible(),
        reversedPoints4,
        original.publishPromotionBenefit());
  }

  private static void requireFrozenMetadata(
      SuccessfulAllocationReversalFact reversal, SuccessfulAllocationFact original) {
    if (!reversal.taskId().equals(original.taskId())
        || !reversal.lotId().equals(original.lotId())
        || !reversal.walletId().equals(original.walletId())
        || reversal.lotType() != original.lotType()
        || reversal.benefitEligible() != original.benefitEligible()
        || reversal.publishPromotionBenefitReversal()
            != original.publishPromotionBenefit()) {
      throw new IllegalArgumentException("existing reversal metadata does not match successful allocation");
    }
  }

  private static boolean sameReversal(
      SuccessfulAllocationReversalFact reversal,
      SuccessfulAllocationReversalRequest request) {
    return reversal.settlementId().equals(request.settlementId())
        && reversal.allocationId().equals(request.allocationId())
        && reversal.reversedPoints4().equals(request.reversedPoints4());
  }

  private record AllocationKey(String settlementId, String allocationId) {}
}
