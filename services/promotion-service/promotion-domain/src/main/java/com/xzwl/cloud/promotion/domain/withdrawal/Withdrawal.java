package com.xzwl.cloud.promotion.domain.withdrawal;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccountSnapshot;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class Withdrawal {

  public static final Decimal4 DEFAULT_MINIMUM = Decimal4.of("100.00");
  private static final Pattern DECIMAL_2 = Pattern.compile("(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?");

  private final String id;
  private final String promoterId;
  private final Decimal4 amount;
  private final Decimal4 minimumAmount;
  private final PayoutAccountSnapshot payoutSnapshot;
  private final String requestCommandId;
  private final Instant requestedAt;
  private final Map<String, CommandReplay> commandReplays = new HashMap<>();
  private WithdrawalStatus status = WithdrawalStatus.WITHDRAWING;
  private WithdrawalRejectionType rejectionType;
  private String rejectionReason;
  private Instant updatedAt;

  private Withdrawal(
      String id,
      String promoterId,
      Decimal4 amount,
      Decimal4 minimumAmount,
      PayoutAccountSnapshot payoutSnapshot,
      String requestCommandId,
      Instant requestedAt) {
    this.id = id;
    this.promoterId = promoterId;
    this.amount = amount;
    this.minimumAmount = minimumAmount;
    this.payoutSnapshot = payoutSnapshot;
    this.requestCommandId = requestCommandId;
    this.requestedAt = requestedAt;
    this.updatedAt = requestedAt;
  }

  public static Withdrawal request(
      String id,
      String promoterId,
      AccountStatus promoterAccountStatus,
      PayoutAccount payoutAccount,
      CommissionBalanceSnapshot commissionBalance,
      String amountInput,
      Decimal4 minimumAmount,
      String commandId,
      Instant requestedAt) {
    String validatedId = PromotionIdentifiers.requireId(id, "id");
    String validatedPromoterId = PromotionIdentifiers.requireId(promoterId, "promoterId");
    Objects.requireNonNull(promoterAccountStatus, "promoterAccountStatus");
    PayoutAccount validatedPayout = Objects.requireNonNull(payoutAccount, "payoutAccount");
    CommissionBalanceSnapshot validatedBalance =
        Objects.requireNonNull(commissionBalance, "commissionBalance");
    Decimal4 validatedAmount = parseAmount(amountInput);
    Decimal4 validatedMinimum = Objects.requireNonNull(minimumAmount, "minimumAmount");
    String validatedCommandId = PromotionIdentifiers.requireId(commandId, "commandId");
    Instant validatedRequestedAt = Objects.requireNonNull(requestedAt, "requestedAt");

    if (promoterAccountStatus != AccountStatus.ENABLED) {
      throw new IllegalStateException("disabled promoter cannot request a withdrawal");
    }
    if (!validatedPromoterId.equals(validatedPayout.promoterId())) {
      throw new IllegalArgumentException("payout account must be owned by the promoter");
    }
    if (validatedPayout.status() != PayoutAccountStatus.ACTIVE) {
      throw new IllegalStateException("payout account must be active");
    }
    if (compare(validatedMinimum, Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("minimum amount must be positive");
    }
    if (compare(validatedAmount, validatedMinimum) < 0) {
      throw new IllegalArgumentException("withdrawal amount is below the minimum");
    }
    if (compare(validatedBalance.cashDebt(), Decimal4.ZERO) > 0) {
      throw new IllegalStateException("cash debt must be cleared before withdrawal");
    }
    if (compare(validatedAmount, validatedBalance.withdrawable()) > 0) {
      throw new IllegalStateException("withdrawal amount exceeds withdrawable balance");
    }

    return new Withdrawal(
        validatedId,
        validatedPromoterId,
        validatedAmount,
        validatedMinimum,
        validatedPayout.snapshot(validatedRequestedAt),
        validatedCommandId,
        validatedRequestedAt);
  }

  public synchronized Withdrawal replayRequest(
      String commandId, String amountInput, String payoutAccountId) {
    String validatedCommandId = PromotionIdentifiers.requireId(commandId, "commandId");
    Decimal4 replayAmount = parseAmount(amountInput);
    String validatedPayoutAccountId =
        PromotionIdentifiers.requireId(payoutAccountId, "payoutAccountId");
    if (!requestCommandId.equals(validatedCommandId)
        || !amount.equals(replayAmount)
        || !payoutSnapshot.accountId().equals(validatedPayoutAccountId)) {
      throw new IllegalStateException("idempotency key was replayed with different request semantics");
    }
    return this;
  }

  public synchronized WithdrawalTransitionResult approve(
      String commandId, String operatorId, Instant changedAt) {
    return transition(
        new CommandSemantics(Operation.APPROVE, requireId(operatorId, "operatorId"), null,
            requireTime(changedAt)),
        commandId,
        WithdrawalStatus.WITHDRAWING,
        WithdrawalStatus.PENDING_PAYOUT,
        null);
  }

  public synchronized WithdrawalTransitionResult reject(
      String commandId, String operatorId, String reason, Instant changedAt) {
    String validatedReason = requireText(reason, "reason");
    WithdrawalBalanceAction action =
        action(
            WithdrawalBalanceActionType.RELEASE_RESERVATION,
            commandId,
            WithdrawalBalanceActionReason.ADMIN_REJECTION,
            null);
    WithdrawalTransitionResult result =
        transition(
            new CommandSemantics(
                Operation.REJECT, requireId(operatorId, "operatorId"), validatedReason,
                requireTime(changedAt)),
            commandId,
            WithdrawalStatus.WITHDRAWING,
            WithdrawalStatus.REJECTED,
            action);
    rejectionType = WithdrawalRejectionType.ADMIN_REJECTED;
    rejectionReason = validatedReason;
    return result;
  }

  public synchronized WithdrawalTransitionResult markPaid(
      String commandId, String operatorId, Instant changedAt) {
    return transition(
        new CommandSemantics(Operation.MARK_PAID, requireId(operatorId, "operatorId"), null,
            requireTime(changedAt)),
        commandId,
        WithdrawalStatus.PENDING_PAYOUT,
        WithdrawalStatus.PAID,
        action(
            WithdrawalBalanceActionType.CONFIRM_PAID,
            commandId,
            WithdrawalBalanceActionReason.OFFLINE_PAYOUT_CONFIRMED,
            null));
  }

  public synchronized WithdrawalTransitionResult compensateChargeback(
      String commandId, String sourceOrderId, Instant changedAt) {
    String validatedOrderId = requireId(sourceOrderId, "sourceOrderId");
    WithdrawalStatus chargebackSource =
        status == WithdrawalStatus.WITHDRAWING
            ? WithdrawalStatus.WITHDRAWING
            : WithdrawalStatus.PENDING_PAYOUT;
    WithdrawalTransitionResult result =
        transition(
            new CommandSemantics(
                Operation.CHARGEBACK,
                validatedOrderId,
                WithdrawalBalanceActionReason.ORIGINAL_ORDER_CHARGEBACK.name(),
                requireTime(changedAt)),
            commandId,
            chargebackSource,
            WithdrawalStatus.REJECTED,
            action(
                WithdrawalBalanceActionType.CHARGEBACK_CLAWBACK,
                commandId,
                WithdrawalBalanceActionReason.ORIGINAL_ORDER_CHARGEBACK,
                validatedOrderId));
    rejectionType = WithdrawalRejectionType.CHARGEBACK_COMPENSATION;
    rejectionReason = "original order chargeback: " + validatedOrderId;
    return result;
  }

  private WithdrawalTransitionResult transition(
      CommandSemantics semantics,
      String commandId,
      WithdrawalStatus required,
      WithdrawalStatus target,
      WithdrawalBalanceAction action) {
    String validatedCommandId = requireId(commandId, "commandId");
    CommandReplay replay = commandReplays.get(validatedCommandId);
    if (replay != null) {
      if (!replay.semantics.equals(semantics)) {
        throw new IllegalStateException("idempotency key was replayed with different semantics");
      }
      return replay.result;
    }
    if (status != required) {
      throw new IllegalStateException("withdrawal status does not allow this command");
    }
    if (semantics.changedAt.isBefore(updatedAt)) {
      throw new IllegalArgumentException("changedAt must not be before updatedAt");
    }
    status = target;
    updatedAt = semantics.changedAt;
    WithdrawalTransitionResult result =
        action == null
            ? WithdrawalTransitionResult.withoutBalanceAction(target)
            : WithdrawalTransitionResult.withBalanceAction(target, action);
    commandReplays.put(validatedCommandId, new CommandReplay(semantics, result));
    return result;
  }

  private WithdrawalBalanceAction action(
      WithdrawalBalanceActionType type,
      String commandId,
      WithdrawalBalanceActionReason reason,
      String referenceId) {
    return new WithdrawalBalanceAction(
        type, id, promoterId, amount, requireId(commandId, "commandId"), reason, referenceId);
  }

  public String id() {
    return id;
  }

  public String promoterId() {
    return promoterId;
  }

  public Decimal4 amount() {
    return amount;
  }

  public Decimal4 minimumAmount() {
    return minimumAmount;
  }

  public PayoutAccountSnapshot payoutSnapshot() {
    return payoutSnapshot;
  }

  public String requestCommandId() {
    return requestCommandId;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public synchronized Instant updatedAt() {
    return updatedAt;
  }

  public synchronized WithdrawalStatus status() {
    return status;
  }

  public synchronized Optional<WithdrawalRejectionType> rejectionType() {
    return Optional.ofNullable(rejectionType);
  }

  public synchronized Optional<String> rejectionReason() {
    return Optional.ofNullable(rejectionReason);
  }

  private static Decimal4 parseAmount(String input) {
    if (input == null || !DECIMAL_2.matcher(input).matches()) {
      throw new IllegalArgumentException("withdrawal amount must have at most two decimal places");
    }
    Decimal4 amount = Decimal4.of(input);
    if (compare(amount, Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("withdrawal amount must be positive");
    }
    return amount;
  }

  private static int compare(Decimal4 left, Decimal4 right) {
    return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
  }

  private static String requireId(String value, String name) {
    return PromotionIdentifiers.requireId(value, name);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Instant requireTime(Instant value) {
    return Objects.requireNonNull(value, "changedAt");
  }

  private enum Operation {
    APPROVE,
    REJECT,
    MARK_PAID,
    CHARGEBACK
  }

  private record CommandSemantics(
      Operation operation, String actorOrSource, String detail, Instant changedAt) {}

  private record CommandReplay(
      CommandSemantics semantics, WithdrawalTransitionResult result) {}
}
