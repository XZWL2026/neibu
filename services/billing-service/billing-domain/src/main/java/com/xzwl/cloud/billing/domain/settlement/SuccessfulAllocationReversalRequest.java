package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record SuccessfulAllocationReversalRequest(
    String reversalId,
    String settlementId,
    String allocationId,
    Decimal4 reversedPoints4) {

  public SuccessfulAllocationReversalRequest {
    TaskSettlement.requireId(reversalId, "reversalId");
    TaskSettlement.requireId(settlementId, "settlementId");
    TaskSettlement.requireId(allocationId, "allocationId");
    Objects.requireNonNull(reversedPoints4, "reversedPoints4");
    if (!reversedPoints4.isPositive()) {
      throw new IllegalArgumentException("reversedPoints4 must be positive");
    }
  }
}
