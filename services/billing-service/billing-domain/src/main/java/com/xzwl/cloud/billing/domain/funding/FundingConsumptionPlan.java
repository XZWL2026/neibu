package com.xzwl.cloud.billing.domain.funding;

import java.util.List;
import java.util.Objects;

public record FundingConsumptionPlan(
    List<FundingLot> updatedLots, List<ConsumptionAllocation> allocations) {

  public FundingConsumptionPlan {
    updatedLots = List.copyOf(Objects.requireNonNull(updatedLots, "updatedLots"));
    allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));
  }
}
