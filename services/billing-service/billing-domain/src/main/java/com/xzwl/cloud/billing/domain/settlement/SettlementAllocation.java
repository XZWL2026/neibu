package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import java.util.Objects;

public record SettlementAllocation(String allocationId, ConsumptionAllocation allocation) {

  public SettlementAllocation {
    TaskSettlement.requireId(allocationId, "allocationId");
    Objects.requireNonNull(allocation, "allocation");
  }
}
