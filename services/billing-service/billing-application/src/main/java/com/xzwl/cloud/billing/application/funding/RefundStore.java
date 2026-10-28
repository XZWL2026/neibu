package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingRefundPlan;
import com.xzwl.cloud.billing.domain.funding.RefundAllocation;
import java.util.List;
import java.util.Optional;

/** Persistence port for wallet refund planning and its source allocations. */
public interface RefundStore {

  Optional<FundingRefundPlan> findRefund(String walletId, String refundBatchId);

  List<FundingLot> loadLots(String walletId);

  List<ConsumptionAllocation> loadAllocations(String walletId);

  List<RefundAllocation> loadRefundAllocations(String walletId);

  void saveRefund(String walletId, String refundBatchId, FundingRefundPlan plan);
}
