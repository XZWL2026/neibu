package com.xzwl.cloud.promotion.application.reward;

import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;
import com.xzwl.cloud.promotion.domain.reward.PointRewardWriteResult;
import java.util.Objects;

/** Orchestrates an idempotent point-reward ledger write and its Billing credit result. */
public final class GrantPointRewardHandler {

  private final PointRewardStore store;

  public GrantPointRewardHandler(PointRewardStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public PointRewardWriteResult handle(GrantPointRewardCommand command) {
    GrantPointRewardCommand requested = Objects.requireNonNull(command, "command");
    PointRewardAccount account = Objects.requireNonNull(
        store.load(requested.beneficiaryUserId()), "point reward account");
    int beforeEntries = account.entries().size();
    PointRewardWriteResult result = account.grant(
        requested.sourceEventId(), requested.rewardEntitlementId(), requested.sourceSettlementId(),
        requested.sourceAllocationId(), requested.sourceFundingLotId(), requested.sourceWalletOwnerUserId(),
        requested.referralBindingVersion(), requested.cashTier(), requested.points(), requested.createdAt());
    if (account.entries().size() != beforeEntries) {
      store.save(account);
    }
    return result;
  }
}
