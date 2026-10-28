package com.xzwl.cloud.promotion.domain.payout;

public sealed interface PayoutAccountDetails
    permits BankCardDetails, AlipayDetails, WechatDetails {

  PayoutAccountType type();

  PayoutAccountMaskedDetails masked();
}
