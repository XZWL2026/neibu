package com.xzwl.cloud.promotion.domain.payout;

public record BankCardDetails(String holderName, String cardNumber, String bankName)
    implements PayoutAccountDetails {

  public BankCardDetails {
    holderName =
        PayoutFieldValidation.requireText(
            holderName, "holderName", PayoutFieldValidation.NAME_MAX_LENGTH);
    cardNumber = PayoutFieldValidation.requireText(cardNumber, "cardNumber", 32);
    if (!cardNumber.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException("cardNumber must contain only digits");
    }
    bankName =
        PayoutFieldValidation.requireText(
            bankName, "bankName", PayoutFieldValidation.BANK_NAME_MAX_LENGTH);
  }

  @Override
  public PayoutAccountType type() {
    return PayoutAccountType.BANK_CARD;
  }

  @Override
  public PayoutAccountMaskedDetails masked() {
    String maskedNumber =
        cardNumber.length() <= 4 ? "****" : cardNumber.substring(cardNumber.length() - 4);
    return new PayoutAccountMaskedDetails(type(), maskedNumber, false);
  }

  @Override
  public String toString() {
    return "BankCardDetails[redacted]";
  }
}
