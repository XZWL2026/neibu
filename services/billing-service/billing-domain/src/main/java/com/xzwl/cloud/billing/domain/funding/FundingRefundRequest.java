package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record FundingRefundRequest(
    String refundId, String operationId, String lotId, Decimal4 refundedPoints4) {

  public FundingRefundRequest {
    FundingLot.requireId(refundId, "refundId");
    FundingLot.requireId(operationId, "operationId");
    FundingLot.requireId(lotId, "lotId");
    Objects.requireNonNull(refundedPoints4, "refundedPoints4");
    if (!refundedPoints4.isPositive()) {
      throw new IllegalArgumentException("refundedPoints4 must be positive");
    }
  }
}
