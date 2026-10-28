package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FundingRefundPlanner {

  private FundingRefundPlanner() {}

  public static FundingRefundPlan plan(
      List<FundingLot> currentLots,
      List<ConsumptionAllocation> originalAllocations,
      List<RefundAllocation> existingRefundAllocations,
      List<FundingRefundRequest> requests) {
    List<FundingLot> lots = List.copyOf(Objects.requireNonNull(currentLots, "currentLots"));
    List<ConsumptionAllocation> allocations =
        List.copyOf(Objects.requireNonNull(originalAllocations, "originalAllocations"));
    List<RefundAllocation> existing =
        List.copyOf(
            Objects.requireNonNull(existingRefundAllocations, "existingRefundAllocations"));
    List<FundingRefundRequest> requested =
        List.copyOf(Objects.requireNonNull(requests, "requests"));
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("requests must not be empty");
    }

    Map<String, FundingLot> lotsById = indexLots(lots);
    Map<AllocationKey, ConsumptionAllocation> allocationsByKey =
        indexAllocations(allocations, lotsById);
    Map<String, RefundAllocation> refundsById = new HashMap<>();
    Map<AllocationKey, Decimal4> refundedByAllocation = new HashMap<>();
    indexExistingRefunds(
        existing, lotsById, allocationsByKey, refundsById, refundedByAllocation);

    Set<String> requestedRefundIds = new HashSet<>();
    Map<String, Decimal4> restoredByLot = new HashMap<>();
    List<RefundAllocation> newRefunds = new ArrayList<>();
    for (FundingRefundRequest request : requested) {
      if (!requestedRefundIds.add(request.refundId())) {
        throw new IllegalArgumentException("duplicate refund request id");
      }
      AllocationKey key = new AllocationKey(request.operationId(), request.lotId());
      ConsumptionAllocation allocation = allocationsByKey.get(key);
      FundingLot lot = lotsById.get(request.lotId());
      if (lot == null || allocation == null) {
        throw new IllegalArgumentException("refund request must reference a known allocation and lot");
      }

      RefundAllocation prior = refundsById.get(request.refundId());
      if (prior != null) {
        if (sameRefund(prior, request)) {
          continue;
        }
        throw new IllegalArgumentException("refund id conflicts with an existing refund");
      }

      Decimal4 total =
          refundedByAllocation
              .getOrDefault(key, Decimal4.zero())
              .add(request.refundedPoints4());
      if (total.compareTo(allocation.allocatedPoints4()) > 0) {
        throw new IllegalArgumentException("cumulative refund exceeds original allocation");
      }
      refundedByAllocation.put(key, total);
      restoredByLot.merge(request.lotId(), request.refundedPoints4(), Decimal4::add);
      newRefunds.add(
          new RefundAllocation(
              request.refundId(),
              request.operationId(),
              request.lotId(),
              allocation.walletId(),
              request.refundedPoints4(),
              allocation.lotType(),
              allocation.benefitEligible()));
    }

    List<FundingLot> updatedLots = new ArrayList<>(lots.size());
    for (FundingLot lot : lots) {
      Decimal4 remaining =
          lot.remainingPoints4().add(restoredByLot.getOrDefault(lot.id(), Decimal4.zero()));
      if (remaining.compareTo(lot.originalPoints4()) > 0) {
        throw new IllegalArgumentException("refunded balance exceeds funding lot original amount");
      }
      updatedLots.add(lot.withRemainingPoints4(remaining));
    }
    return new FundingRefundPlan(updatedLots, newRefunds);
  }

  private static Map<String, FundingLot> indexLots(List<FundingLot> lots) {
    Map<String, FundingLot> indexed = new LinkedHashMap<>();
    for (FundingLot lot : lots) {
      if (indexed.put(lot.id(), lot) != null) {
        throw new IllegalArgumentException("duplicate funding lot id");
      }
    }
    return indexed;
  }

  private static Map<AllocationKey, ConsumptionAllocation> indexAllocations(
      List<ConsumptionAllocation> allocations, Map<String, FundingLot> lotsById) {
    Map<AllocationKey, ConsumptionAllocation> indexed = new HashMap<>();
    for (ConsumptionAllocation allocation : allocations) {
      FundingLot lot = lotsById.get(allocation.lotId());
      if (lot == null) {
        throw new IllegalArgumentException("consumption allocation references an unknown lot");
      }
      requireMatchingFundingSemantics(
          allocation.walletId(), allocation.lotType(), allocation.benefitEligible(), lot);
      AllocationKey key = new AllocationKey(allocation.operationId(), allocation.lotId());
      if (indexed.put(key, allocation) != null) {
        throw new IllegalArgumentException("duplicate consumption allocation key");
      }
    }
    return indexed;
  }

  private static void indexExistingRefunds(
      List<RefundAllocation> existing,
      Map<String, FundingLot> lotsById,
      Map<AllocationKey, ConsumptionAllocation> allocationsByKey,
      Map<String, RefundAllocation> refundsById,
      Map<AllocationKey, Decimal4> refundedByAllocation) {
    for (RefundAllocation refund : existing) {
      if (refundsById.put(refund.refundId(), refund) != null) {
        throw new IllegalArgumentException("duplicate existing refund id");
      }
      AllocationKey key = new AllocationKey(refund.operationId(), refund.lotId());
      ConsumptionAllocation allocation = allocationsByKey.get(key);
      FundingLot lot = lotsById.get(refund.lotId());
      if (allocation == null || lot == null) {
        throw new IllegalArgumentException("existing refund references an unknown allocation or lot");
      }
      requireMatchingFundingSemantics(
          refund.walletId(), refund.lotType(), refund.benefitEligible(), lot);
      if (!refund.walletId().equals(allocation.walletId())
          || refund.lotType() != allocation.lotType()
          || refund.benefitEligible() != allocation.benefitEligible()) {
        throw new IllegalArgumentException("refund allocation does not match consumption allocation");
      }
      Decimal4 total =
          refundedByAllocation
              .getOrDefault(key, Decimal4.zero())
              .add(refund.refundedPoints4());
      if (total.compareTo(allocation.allocatedPoints4()) > 0) {
        throw new IllegalArgumentException("cumulative refund exceeds original allocation");
      }
      refundedByAllocation.put(key, total);
    }
  }

  private static void requireMatchingFundingSemantics(
      String walletId, FundingLotType lotType, boolean benefitEligible, FundingLot lot) {
    if (!walletId.equals(lot.walletId())
        || lotType != lot.type()
        || benefitEligible != lot.benefitEligible()) {
      throw new IllegalArgumentException("allocation does not match funding lot");
    }
  }

  private static boolean sameRefund(
      RefundAllocation allocation, FundingRefundRequest request) {
    return allocation.operationId().equals(request.operationId())
        && allocation.lotId().equals(request.lotId())
        && allocation.refundedPoints4().equals(request.refundedPoints4());
  }

  private record AllocationKey(String operationId, String lotId) {}
}
