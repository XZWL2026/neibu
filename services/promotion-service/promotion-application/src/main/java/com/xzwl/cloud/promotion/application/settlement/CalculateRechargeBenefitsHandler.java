package com.xzwl.cloud.promotion.application.settlement;

import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.settlement.RechargeBenefitInput;
import com.xzwl.cloud.promotion.domain.settlement.RechargeBenefitResult;
import java.util.Objects;

public final class CalculateRechargeBenefitsHandler {
  private final BenefitPolicyHistory policyHistory;

  public CalculateRechargeBenefitsHandler(BenefitPolicyHistory policyHistory) {
    this.policyHistory = Objects.requireNonNull(policyHistory, "policyHistory");
  }

  public RechargeBenefitResult handle(RechargeBenefitCommand command) {
    RechargeBenefitCommand requested = Objects.requireNonNull(command, "command");
    BenefitPolicyVersion policy = policyHistory.effectiveAt(requested.orderCreatedAt())
        .orElseThrow(() -> new IllegalArgumentException("no benefit policy is effective"));
    BenefitSnapshot benefits = BenefitSnapshot.cashOnly(requested.promoterUserId());
    return com.xzwl.cloud.promotion.domain.settlement.RechargeBenefitCalculator.calculate(
        new RechargeBenefitInput(
            requested.sourceEventId(), requested.orderId(), requested.fundingLotId(),
            requested.walletOwnerUserId(), requested.orderCreatedAt(),
            requested.referralBindingVersion(), benefits, requested.paidAmount4(),
            requested.paidAmount4(),
            policy.cashRate(requested.promoterUserId(), benefits.cashTier()),
            policy.pointRate(), true));
  }
}
