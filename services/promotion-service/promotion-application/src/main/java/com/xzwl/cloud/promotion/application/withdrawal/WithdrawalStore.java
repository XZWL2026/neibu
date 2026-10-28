package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import java.util.Optional;

/** Persistence and account-read port used by the withdrawal request use case. */
public interface WithdrawalStore {

  Optional<Withdrawal> findByRequestCommandId(String promoterId, String commandId);

  AccountStatus loadPromoterAccountStatus(String promoterId);

  PayoutAccount loadPayoutAccount(String promoterId, String payoutAccountId);

  CommissionBalanceSnapshot loadCommissionBalance(String promoterId);

  void save(Withdrawal withdrawal);
}
