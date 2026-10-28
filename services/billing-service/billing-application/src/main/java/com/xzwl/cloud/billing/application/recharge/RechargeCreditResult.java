package com.xzwl.cloud.billing.application.recharge;

import com.xzwl.cloud.billing.domain.wallet.WalletBalance;

public record RechargeCreditResult(WalletBalance balance, RechargeCreditRecord record) {}
