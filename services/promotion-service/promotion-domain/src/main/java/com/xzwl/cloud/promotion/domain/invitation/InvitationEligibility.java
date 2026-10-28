package com.xzwl.cloud.promotion.domain.invitation;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import java.util.Objects;
import java.util.Set;

public record InvitationEligibility(AccountType accountType, AccountStatus accountStatus) {

  private static final Set<AccountType> ELIGIBLE_ACCOUNT_TYPES =
      Set.of(AccountType.PERSONAL, AccountType.ENTERPRISE, AccountType.API);

  public InvitationEligibility {
    accountType = Objects.requireNonNull(accountType, "accountType");
    accountStatus = Objects.requireNonNull(accountStatus, "accountStatus");
  }

  public void requireEligible() {
    if (!isEligible()) {
      throw new IneligibleInvitationAccountException();
    }
  }

  boolean isEligible() {
    return accountStatus == AccountStatus.ENABLED && ELIGIBLE_ACCOUNT_TYPES.contains(accountType);
  }
}
