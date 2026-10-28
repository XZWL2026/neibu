package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FundingLotFifoPlanner {

  private static final Comparator<FundingLot> FIFO_ORDER =
      Comparator.comparing(FundingLot::creditedAt).thenComparing(FundingLot::id);

  private FundingLotFifoPlanner() {}

  public static FundingConsumptionPlan plan(
      String operationId, String walletId, Decimal4 requestedPoints4, List<FundingLot> lots) {
    FundingLot.requireId(operationId, "operationId");
    FundingLot.requireId(walletId, "walletId");
    Objects.requireNonNull(requestedPoints4, "requestedPoints4");
    if (!requestedPoints4.isPositive()) {
      throw new IllegalArgumentException("requestedPoints4 must be positive");
    }

    List<FundingLot> ordered = new ArrayList<>(Objects.requireNonNull(lots, "lots"));
    Set<String> lotIds = new HashSet<>();
    Decimal4 available = Decimal4.zero();
    for (FundingLot lot : ordered) {
      Objects.requireNonNull(lot, "lot");
      if (!walletId.equals(lot.walletId())) {
        throw new IllegalArgumentException("all lots must belong to the requested wallet");
      }
      if (!lotIds.add(lot.id())) {
        throw new IllegalArgumentException("duplicate funding lot id");
      }
      available = available.add(lot.remainingPoints4());
    }
    if (available.compareTo(requestedPoints4) < 0) {
      throw new IllegalArgumentException("insufficient funding lot balance");
    }

    ordered.sort(FIFO_ORDER);
    List<FundingLot> updatedLots = new ArrayList<>(ordered.size());
    List<ConsumptionAllocation> allocations = new ArrayList<>();
    Decimal4 unallocated = requestedPoints4;
    for (FundingLot lot : ordered) {
      Decimal4 allocated =
          unallocated.compareTo(lot.remainingPoints4()) < 0
              ? unallocated
              : lot.remainingPoints4();
      if (allocated.isPositive()) {
        updatedLots.add(lot.withRemainingPoints4(lot.remainingPoints4().subtract(allocated)));
        allocations.add(
            new ConsumptionAllocation(
                operationId,
                lot.id(),
                walletId,
                allocated,
                lot.type(),
                lot.benefitEligible()));
        unallocated = unallocated.subtract(allocated);
      } else {
        updatedLots.add(lot);
      }
    }
    if (unallocated.compareTo(Decimal4.zero()) != 0) {
      throw new IllegalStateException("allocation total does not match request");
    }
    return new FundingConsumptionPlan(updatedLots, allocations);
  }
}
