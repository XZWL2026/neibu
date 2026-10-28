package com.xzwl.cloud.promotion.domain.withdrawal;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Objects;

public final class WithdrawalBalanceAction {

  private final WithdrawalBalanceActionType type;
  private final String withdrawalId;
  private final String promoterId;
  private final Decimal4 amount;
  private final String commandId;
  private final WithdrawalBalanceActionReason reason;
  private final String referenceId;

  WithdrawalBalanceAction(
      WithdrawalBalanceActionType type,
      String withdrawalId,
      String promoterId,
      Decimal4 amount,
      String commandId,
      WithdrawalBalanceActionReason reason,
      String referenceId) {
    this.type = Objects.requireNonNull(type, "type");
    this.withdrawalId = PromotionIdentifiers.requireId(withdrawalId, "withdrawalId");
    this.promoterId = PromotionIdentifiers.requireId(promoterId, "promoterId");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.commandId = PromotionIdentifiers.requireId(commandId, "commandId");
    this.reason = Objects.requireNonNull(reason, "reason");
    this.referenceId = PromotionIdentifiers.optionalId(referenceId, "referenceId");
  }

  public WithdrawalBalanceActionType type() {
    return type;
  }

  public String withdrawalId() {
    return withdrawalId;
  }

  public String promoterId() {
    return promoterId;
  }

  public Decimal4 amount() {
    return amount;
  }

  public String commandId() {
    return commandId;
  }

  public WithdrawalBalanceActionReason reason() {
    return reason;
  }

  public String referenceId() {
    return referenceId;
  }
}
