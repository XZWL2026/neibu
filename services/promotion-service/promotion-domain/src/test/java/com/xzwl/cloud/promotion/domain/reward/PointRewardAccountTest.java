package com.xzwl.cloud.promotion.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PointRewardAccountTest {

  private static final Instant T0 = Instant.parse("2026-08-30T12:00:00Z");

  @Test
  void grantsDirectRewardForNoneLevelOneAndLevelTwoFrozenCashSources() {
    for (CashTier tier : CashTier.values()) {
      PointRewardAccount account = PointRewardAccount.create("direct-beneficiary-" + tier);

      PointRewardWriteResult result = grant(account, "grant-" + tier, "entitlement-" + tier, tier, "10");

      assertThat(result.entries()).hasSize(1);
      ReferralPointEntitlement fact = result.entries().getFirst();
      assertThat(fact.actionType()).isEqualTo(PointRewardActionType.POINT_REWARD_GRANTED);
      assertThat(fact.cashTier()).isEqualTo(tier);
      assertThat(fact.referralBindingVersion()).isEqualTo(8L);
      assertThat(fact.sourceSettlementId()).isEqualTo("settlement");
      assertThat(fact.sourceAllocationId()).isEqualTo("allocation-entitlement-" + tier);
      assertThat(fact.sourceFundingLotId()).isEqualTo("funding-lot");
      assertThat(fact.sourceWalletOwnerUserId()).isEqualTo("consumer");
      assertThat(result.netRewardToCredit()).isEqualTo(amount("10"));
      assertThat(result.snapshot().cumulativeGranted()).isEqualTo(amount("10"));
      assertThat(result.snapshot().cumulativeReversed()).isEqualTo(Decimal4.ZERO);
      assertThat(result.snapshot().pointDebt()).isEqualTo(Decimal4.ZERO);
    }
  }

  @Test
  void futureGrantOffsetsOnlyPointDebtAndReturnsOnlyNetPointsForBilling() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant-1", "entitlement-1", CashTier.NONE, "10");
    reverse(account, "reverse-1", "entitlement-1", CashTier.NONE, "8", "2");

    PointRewardWriteResult result =
        grant(account, "grant-2", "entitlement-2", CashTier.LEVEL_1, "9");

    assertThat(result.entries())
        .extracting(ReferralPointEntitlement::actionType)
        .containsExactly(
            PointRewardActionType.POINT_REWARD_GRANTED,
            PointRewardActionType.POINT_DEBT_OFFSET);
    assertThat(result.entries().getLast().points4()).isEqualTo(amount("6"));
    assertThat(result.snapshot().pointDebt()).isEqualTo(Decimal4.ZERO);
    assertThat(result.snapshot().cumulativeGranted()).isEqualTo(amount("19"));
    assertThat(result.netRewardToCredit()).isEqualTo(amount("3"));
  }

  @Test
  void reverseUsesExactEntitlementAndCreatesDebtOnlyForBillingConfirmedShortfall() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant", "entitlement", CashTier.LEVEL_2, "10");

    PointRewardWriteResult partial =
        reverse(account, "reverse-1", "entitlement", CashTier.LEVEL_2, "4", "4");
    PointRewardWriteResult shortfall =
        reverse(account, "reverse-2", "entitlement", CashTier.LEVEL_2, "6", "1.5");

    assertThat(partial.entries())
        .extracting(ReferralPointEntitlement::actionType)
        .containsExactly(PointRewardActionType.POINT_REWARD_REVERSED);
    assertThat(partial.snapshot().pointDebt()).isEqualTo(Decimal4.ZERO);
    assertThat(shortfall.entries())
        .extracting(ReferralPointEntitlement::actionType)
        .containsExactly(
            PointRewardActionType.POINT_REWARD_REVERSED,
            PointRewardActionType.POINT_DEBT_CREATED);
    assertThat(shortfall.entries().getLast().points4()).isEqualTo(amount("4.5"));
    assertThat(shortfall.snapshot().cumulativeReversed()).isEqualTo(amount("10"));
    assertThat(shortfall.snapshot().pointDebt()).isEqualTo(amount("4.5"));
    assertThat(shortfall.netRewardToCredit()).isEqualTo(Decimal4.ZERO);
  }

  @Test
  void reverseMustRepeatTheOriginalFrozenAllocationSnapshot() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant", "entitlement", CashTier.LEVEL_1, "10");

    assertThatThrownBy(
            () ->
                account.reverse(
                    "reverse",
                    "entitlement",
                    "other-settlement",
                    "allocation-entitlement",
                    "funding-lot",
                    "consumer",
                    8,
                    CashTier.LEVEL_1,
                    amount("1"),
                    amount("1"),
                    T0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source snapshot");
    assertThat(account.currentSnapshot().cumulativeReversed()).isEqualTo(Decimal4.ZERO);
  }

  @Test
  void cumulativeReverseCannotExceedOriginalGrant() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant", "entitlement", CashTier.NONE, "3");
    reverse(account, "reverse-1", "entitlement", CashTier.NONE, "2", "2");

    assertThatThrownBy(
            () -> reverse(account, "reverse-2", "entitlement", CashTier.NONE, "1.0001", "1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exceeds");
    assertThat(account.currentSnapshot().cumulativeReversed()).isEqualTo(amount("2"));
  }

  @Test
  void exactReplayReturnsOriginalCompoundFactsAndConflictingReplayIsRejected() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant", "entitlement-1", CashTier.LEVEL_1, "10");
    reverse(account, "reverse", "entitlement-1", CashTier.LEVEL_1, "8", "2");

    PointRewardWriteResult first =
        grant(account, "future", "entitlement-2", CashTier.NONE, "9");
    PointRewardWriteResult replay =
        grant(account, "future", "entitlement-2", CashTier.NONE, "9");

    assertThat(replay.entries()).containsExactlyElementsOf(first.entries());
    assertThat(replay.netRewardToCredit()).isEqualTo(first.netRewardToCredit());
    assertThat(account.entries()).hasSize(5);
    assertThatThrownBy(() -> grant(account, "future", "entitlement-2", CashTier.NONE, "8"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
    assertThat(account.entries()).hasSize(5);
  }

  @Test
  void oneEntitlementCanOnlyBeGrantedOnceEvenWithAnotherEvent() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    grant(account, "grant-1", "entitlement", CashTier.NONE, "3");

    assertThatThrownBy(() -> grant(account, "grant-2", "entitlement", CashTier.NONE, "3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entitlement");
  }

  @Test
  void decimalInputsMustBePositiveAfterNormalizationAndRecoveredMustBeBounded() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");

    assertThatThrownBy(() -> grant(account, "negative", "entitlement-negative", CashTier.NONE, "-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> grant(account, "zero", "entitlement-zero", CashTier.NONE, "0.00009"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    grant(account, "grant", "entitlement", CashTier.NONE, "2");
    assertThatThrownBy(
            () -> reverse(account, "reverse-negative", "entitlement", CashTier.NONE, "1", "-0.1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recoveredNow");
    assertThatThrownBy(
            () -> reverse(account, "reverse-too-much", "entitlement", CashTier.NONE, "1", "1.1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recoveredNow");
    assertThatThrownBy(
            () ->
                account.grant(
                    "overflow-event",
                    "overflow-entitlement",
                    "settlement",
                    "allocation-overflow",
                    "funding-lot",
                    "consumer",
                    8,
                    CashTier.NONE,
                    Decimal4.parse("999999999999999.9999"),
                    T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
  }

  @Test
  void entriesAndWriteResultsAreImmutableAndDoNotExposeWalletBalanceOrWithdrawal() {
    PointRewardAccount account = PointRewardAccount.create("direct-beneficiary");
    PointRewardWriteResult result = grant(account, "grant", "entitlement", CashTier.NONE, "1");

    assertThatThrownBy(() -> account.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(PointRewardAccount.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain("withdraw", "requestWithdrawal", "availablePoints", "walletBalance");
    assertThat(PointRewardSnapshot.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("cumulativeGranted", "cumulativeReversed", "pointDebt");
  }

  private static PointRewardWriteResult grant(
      PointRewardAccount account,
      String eventId,
      String entitlementId,
      CashTier tier,
      String points) {
    return account.grant(
        eventId,
        entitlementId,
        "settlement",
        "allocation-" + entitlementId,
        "funding-lot",
        "consumer",
        8,
        tier,
        amount(points),
        T0);
  }

  private static PointRewardWriteResult reverse(
      PointRewardAccount account,
      String eventId,
      String entitlementId,
      CashTier tier,
      String points,
      String recovered) {
    return account.reverse(
        eventId,
        entitlementId,
        "settlement",
        "allocation-" + entitlementId,
        "funding-lot",
        "consumer",
        8,
        tier,
        amount(points),
        amount(recovered),
        T0);
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
