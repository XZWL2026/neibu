package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.FundingConsumptionPlan;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import java.util.List;
import java.util.Optional;

/** Persistence port for wallet funding lots and idempotent consumption plans. */
public interface FundingStore {

  Optional<FundingConsumptionPlan> findConsumption(String walletId, String operationId);

  List<FundingLot> loadLots(String walletId);

  void saveConsumption(String walletId, String operationId, FundingConsumptionPlan plan);
}
