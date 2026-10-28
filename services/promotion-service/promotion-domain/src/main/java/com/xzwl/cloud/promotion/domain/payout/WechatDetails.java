package com.xzwl.cloud.promotion.domain.payout;

import java.util.Optional;

public record WechatDetails(
    String realName, String boundPhone, Optional<String> qrCodeReference)
    implements PayoutAccountDetails {

  public WechatDetails {
    realName =
        PayoutFieldValidation.requireText(
            realName, "realName", PayoutFieldValidation.NAME_MAX_LENGTH);
    boundPhone = PayoutFieldValidation.requireMobileNumber(boundPhone, "boundPhone");
    qrCodeReference =
        PayoutFieldValidation.requireOptionalText(
            qrCodeReference,
            "qrCodeReference",
            PayoutFieldValidation.QR_REFERENCE_MAX_LENGTH);
  }

  @Override
  public PayoutAccountType type() {
    return PayoutAccountType.WECHAT;
  }

  @Override
  public PayoutAccountMaskedDetails masked() {
    return new PayoutAccountMaskedDetails(
        type(), PayoutFieldValidation.maskMobile(boundPhone), qrCodeReference.isPresent());
  }

  @Override
  public String toString() {
    return "WechatDetails[redacted, qrCodeConfigured=" + qrCodeReference.isPresent() + ']';
  }
}
