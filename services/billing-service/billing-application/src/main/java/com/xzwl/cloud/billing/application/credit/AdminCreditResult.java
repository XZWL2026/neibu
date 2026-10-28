package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.wallet.WalletBalance;

/** Result of an atomic balance and back-office record write. */
public record AdminCreditResult(WalletBalance balance, AdminCreditRecord record) {}
