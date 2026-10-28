package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.FundingRefundRequest;
import java.util.List;
import java.util.Objects;

/** Application input for restoring points consumed by a wallet operation. */
public record RefundFundingCommand(
    String walletId, String refundBatchId, List<FundingRefundRequest> requests) {

  public RefundFundingCommand {
    requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
  }
}
