package com.xzwl.cloud.billing.domain.funding;

import java.util.List;
import java.util.Objects;

public record FundingRefundPlan(
    List<FundingLot> updatedLots, List<RefundAllocation> refundAllocations) {

  public FundingRefundPlan {
    updatedLots = List.copyOf(Objects.requireNonNull(updatedLots, "updatedLots"));
    refundAllocations =
        List.copyOf(Objects.requireNonNull(refundAllocations, "refundAllocations"));
  }
}
