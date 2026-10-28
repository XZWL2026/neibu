package com.xzwl.cloud.promotion.domain.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CommissionWriteResultBoundaryTest {

  private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");

  @Test
  void resultHasNoPublicConstructor() {
    assertThat(CommissionWriteResult.class.getConstructors()).isEmpty();
    assertThat(Modifier.isFinal(CommissionWriteResult.class.getModifiers())).isTrue();
    assertThat(CommissionWriteResult.class.isRecord()).isFalse();
    assertThat(CommissionWriteResult.class.getDeclaredConstructors()).hasSize(1);
  }

  @ParameterizedTest
  @MethodSource("compoundResults")
  void rejectsTruncatedCompoundFacts(CommissionWriteResult result) {
    CommissionEntry primary = result.entries().getFirst();

    assertThatThrownBy(
            () -> CommissionWriteResult.fromLedgerFacts(List.of(primary), primary.after()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  @ParameterizedTest
  @MethodSource("compoundResults")
  void rejectsCompoundFollowUpWithMismatchedSourceIdentity(CommissionWriteResult result) {
    CommissionEntry followUp = result.entries().getLast();
    CommissionEntry mutated =
        copy(
            followUp,
            followUp.sourceEventId() + "-other",
            followUp.withdrawalRequestId(),
            followUp.createdAt());

    assertThatThrownBy(
            () ->
                CommissionWriteResult.fromLedgerFacts(
                    List.of(result.entries().getFirst(), mutated), mutated.after()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  @Test
  void rejectsReorderedAppendedAndDifferentlyTimedFacts() {
    CommissionWriteResult result = releaseWithDebtOffset();
    CommissionEntry primary = result.entries().getFirst();
    CommissionEntry followUp = result.entries().getLast();
    CommissionEntry differentlyTimed =
        copy(followUp, followUp.sourceEventId(), followUp.withdrawalRequestId(), NOW.plusSeconds(1));

    assertThatThrownBy(
            () ->
                CommissionWriteResult.fromLedgerFacts(
                    List.of(followUp, primary), primary.after()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CommissionWriteResult.fromLedgerFacts(
                    List.of(primary, followUp, followUp), followUp.after()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CommissionWriteResult.fromLedgerFacts(
                    List.of(primary, differentlyTimed), differentlyTimed.after()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  @Test
  void validResultIsDefensiveValueObjectWithRedactedString() {
    CommissionWriteResult source = releaseWithDebtOffset();
    ArrayList<CommissionEntry> mutable = new ArrayList<>(source.entries());
    CommissionWriteResult copy =
        CommissionWriteResult.fromLedgerFacts(mutable, source.snapshot());

    mutable.clear();

    assertThat(copy.entries()).containsExactlyElementsOf(source.entries());
    assertThat(copy).isEqualTo(source).hasSameHashCodeAs(source);
    assertThat(copy.toString())
        .doesNotContain("promoter-secret")
        .doesNotContain("source-secret")
        .doesNotContain("order-secret")
        .doesNotContain("lot-secret")
        .doesNotContain("allocation-secret");
  }

  private static Stream<CommissionWriteResult> compoundResults() {
    return Stream.of(releaseWithDebtOffset(), reversalWithDebtCreation(), rejectionWithDebtOffset());
  }

  private static CommissionWriteResult releaseWithDebtOffset() {
    CommissionAccount account = CommissionAccount.create("promoter-secret");
    account.freezeCommission(
        "freeze-a", "order-a", "lot-a", amount("10"), 1, CashTier.LEVEL_1, NOW);
    account.releaseCommission(
        "release-a", "order-a", "lot-a", "allocation-a", amount("10"), NOW);
    account.requestWithdrawal("request-a", "withdrawal-a", amount("10"), NOW);
    account.reverseRelease("reverse-a", "allocation-a", amount("10"), NOW);
    account.freezeCommission(
        "freeze-secret", "order-secret", "lot-secret", amount("4"), 2, CashTier.LEVEL_1, NOW);
    return account.releaseCommission(
        "source-secret",
        "order-secret",
        "lot-secret",
        "allocation-secret",
        amount("4"),
        NOW);
  }

  private static CommissionWriteResult reversalWithDebtCreation() {
    CommissionAccount account = fundedAccount();
    account.requestWithdrawal("request-reversal", "withdrawal-reversal", amount("8"), NOW);
    return account.reverseRelease("reverse-compound", "allocation", amount("10"), NOW);
  }

  private static CommissionWriteResult rejectionWithDebtOffset() {
    CommissionAccount account = fundedAccount();
    account.requestWithdrawal("request-reject", "withdrawal-reject", amount("8"), NOW);
    account.reverseRelease("reverse-reject", "allocation", amount("10"), NOW);
    return account.rejectWithdrawal(
        "reject-compound",
        "withdrawal-reject",
        CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
        NOW);
  }

  private static CommissionAccount fundedAccount() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission(
        "freeze", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, NOW);
    account.releaseCommission("release", "order", "lot", "allocation", amount("10"), NOW);
    return account;
  }

  private static CommissionEntry copy(
      CommissionEntry entry,
      String sourceEventId,
      String withdrawalRequestId,
      Instant createdAt) {
    return new CommissionEntry(
        entry.sequenceNo(),
        entry.beneficiaryPromoterUserId(),
        entry.actionType(),
        entry.amount4(),
        entry.before(),
        entry.after(),
        sourceEventId,
        entry.orderId(),
        entry.fundingLotId(),
        entry.allocationId(),
        withdrawalRequestId,
        entry.referralBindingVersion(),
        entry.cashTier(),
        createdAt,
        entry.reason());
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
