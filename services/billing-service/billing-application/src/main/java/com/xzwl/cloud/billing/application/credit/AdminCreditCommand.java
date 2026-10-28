package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;

/** Internal command for an administrator's manual points credit. */
public record AdminCreditCommand(
    String requestId,
    String walletId,
    String keyId,
    Decimal4 points4,
    String adminId,
    Instant at,
    String remark) {}
