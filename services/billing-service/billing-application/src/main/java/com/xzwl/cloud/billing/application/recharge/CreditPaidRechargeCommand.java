package com.xzwl.cloud.billing.application.recharge;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;

/** Internal input from a verified PAID order; never accept its rate from a public request. */
public record CreditPaidRechargeCommand(
    String orderId, String userId, String walletId, String keyId,
    Decimal4 amount4, Decimal4 rateSnapshot4, Instant paidAt) {}
