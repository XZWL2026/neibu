package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.money.Rate6;
import java.time.Instant;

/** Application input for changing one promoter's effective cash commission rates. */
public record UpdatePromoterCashRateOverrideCommand(
    String historyId,
    long expectedVersionNo,
    Instant effectiveFrom,
    String promoterUserId,
    Rate6 level1CashRate6,
    Rate6 level2CashRate6) {}
