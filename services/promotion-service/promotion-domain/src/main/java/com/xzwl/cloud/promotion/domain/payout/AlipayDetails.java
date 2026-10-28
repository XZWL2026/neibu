package com.xzwl.cloud.promotion.domain.payout;

import java.util.Optional;

public record AlipayDetails(
    String realName, String account, Optional<String> qrCodeReference)
    implements PayoutAccountDetails {

  public AlipayDetails {
    realName =
        PayoutFieldValidation.requireText(
            realName, "realName", PayoutFieldValidation.NAME_MAX_LENGTH);
    account =
        PayoutFieldValidation.requireText(
            account, "account", PayoutFieldValidation.ACCOUNT_MAX_LENGTH);
    qrCodeReference =
        PayoutFieldValidation.requireOptionalText(
            qrCodeReference,
            "qrCodeReference",
            PayoutFieldValidation.QR_REFERENCE_MAX_LENGTH);
  }

  @Override
  public PayoutAccountType type() {
    return PayoutAccountType.ALIPAY;
  }

  @Override
  public PayoutAccountMaskedDetails masked() {
    return new PayoutAccountMaskedDetails(
        type(), PayoutFieldValidation.maskAlipayAccount(account), qrCodeReference.isPresent());
  }

  @Override
  public String toString() {
    return "AlipayDetails[redacted, qrCodeConfigured=" + qrCodeReference.isPresent() + ']';
  }
}
