package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.policy.PromoterCashRateOverride;
import java.time.Instant;
import java.util.Map;

/** Application input for publishing a new effective-dated benefit policy version. */
public record AppendBenefitPolicyVersionCommand(
    String historyId,
    long expectedVersionNo,
    Instant effectiveFrom,
    Rate6 globalPointRate6,
    Rate6 defaultLevel1CashRate6,
    Rate6 defaultLevel2CashRate6,
    Map<String, PromoterCashRateOverride> cashRateOverrides) {}
