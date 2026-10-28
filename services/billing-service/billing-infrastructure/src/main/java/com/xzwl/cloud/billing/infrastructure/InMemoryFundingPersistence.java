package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.funding.FundingStore;
import com.xzwl.cloud.billing.application.funding.RefundStore;
import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.funding.FundingConsumptionPlan;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingRefundPlan;
import com.xzwl.cloud.billing.domain.funding.RefundAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pre-development persistence adapter for funding lots, allocations and refunds. */
public final class InMemoryFundingPersistence implements FundingStore, RefundStore {

  private final Map<String, Map<String, FundingLot>> lotsByWallet = new HashMap<>();
  private final Map<String, FundingConsumptionPlan> consumptions = new HashMap<>();
  private final Map<String, FundingRefundPlan> refunds = new HashMap<>();
  private final Map<String, List<ConsumptionAllocation>> allocationsByWallet = new HashMap<>();
  private final Map<String, List<RefundAllocation>> refundAllocationsByWallet = new HashMap<>();

  public synchronized void creditLot(FundingLot lot) {
    FundingLot requested = Objects.requireNonNull(lot, "lot");
    Map<String, FundingLot> lots = lotsByWallet.computeIfAbsent(requested.walletId(), ignored -> new LinkedHashMap<>());
    if (lots.putIfAbsent(requested.id(), requested) != null) {
      throw new IllegalStateException("funding lot id already exists");
    }
  }

  @Override
  public synchronized Optional<FundingConsumptionPlan> findConsumption(
      String walletId, String operationId) {
    return Optional.ofNullable(consumptions.get(key(walletId, operationId)));
  }

  @Override
  public synchronized List<FundingLot> loadLots(String walletId) {
    return List.copyOf(lotsByWallet.getOrDefault(walletId, Map.of()).values());
  }

  @Override
  public synchronized void saveConsumption(
      String walletId, String operationId, FundingConsumptionPlan plan) {
    Objects.requireNonNull(plan, "plan");
    String storageKey = key(walletId, operationId);
    if (consumptions.putIfAbsent(storageKey, plan) != null) {
      throw new IllegalStateException("consumption already exists");
    }
    replaceLots(walletId, plan.updatedLots());
    allocationsByWallet
        .computeIfAbsent(walletId, ignored -> new ArrayList<>())
        .addAll(plan.allocations());
  }

  @Override
  public synchronized Optional<FundingRefundPlan> findRefund(String walletId, String refundBatchId) {
    return Optional.ofNullable(refunds.get(key(walletId, refundBatchId)));
  }

  @Override
  public synchronized List<ConsumptionAllocation> loadAllocations(String walletId) {
    return List.copyOf(allocationsByWallet.getOrDefault(walletId, List.of()));
  }

  @Override
  public synchronized List<RefundAllocation> loadRefundAllocations(String walletId) {
    return List.copyOf(refundAllocationsByWallet.getOrDefault(walletId, List.of()));
  }

  @Override
  public synchronized void saveRefund(String walletId, String refundBatchId, FundingRefundPlan plan) {
    Objects.requireNonNull(plan, "plan");
    String storageKey = key(walletId, refundBatchId);
    if (refunds.putIfAbsent(storageKey, plan) != null) {
      throw new IllegalStateException("refund already exists");
    }
    replaceLots(walletId, plan.updatedLots());
    refundAllocationsByWallet
        .computeIfAbsent(walletId, ignored -> new ArrayList<>())
        .addAll(plan.refundAllocations());
  }

  private void replaceLots(String walletId, List<FundingLot> updatedLots) {
    Map<String, FundingLot> replacement = new LinkedHashMap<>();
    for (FundingLot lot : updatedLots) {
      if (!walletId.equals(lot.walletId())) {
        throw new IllegalArgumentException("funding lot belongs to a different wallet");
      }
      if (replacement.put(lot.id(), lot) != null) {
        throw new IllegalArgumentException("duplicate funding lot id");
      }
    }
    lotsByWallet.put(walletId, replacement);
  }

  private static String key(String walletId, String operationId) {
    return Objects.requireNonNull(walletId, "walletId") + "\u0000"
        + Objects.requireNonNull(operationId, "operationId");
  }
}
