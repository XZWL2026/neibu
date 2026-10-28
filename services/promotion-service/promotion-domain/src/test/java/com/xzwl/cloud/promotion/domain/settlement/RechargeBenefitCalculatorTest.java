package com.xzwl.cloud.promotion.domain.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RechargeBenefitCalculatorTest {

  private static final Instant ORDER_CREATED_AT = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void truncatesFrozenCashAndCopiesOnlyTheFrozenOrderSnapshot() {
    RechargeBenefitInput input = eligible(
        "user-c",
        BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "33.3333",
        "300.0000",
        "0.030000",
        "0.050000");

    RechargeBenefitResult result = RechargeBenefitCalculator.calculate(input);

    assertThat(result.commissionFreeze()).contains(new CommissionFreezeAction(
        "event-1",
        "order-1",
        "lot-1",
        "user-a",
        7,
        CashTier.LEVEL_2,
        Decimal4.of("0.9999")));
    assertThat(result.fundingBenefitSnapshot()).isEqualTo(new FundingBenefitSnapshot(
        "order-1",
        "lot-1",
        "user-c",
        ORDER_CREATED_AT,
        7L,
        "user-b",
        "user-a",
        CashTier.LEVEL_2,
        Rate6.of("0.030000"),
        Rate6.of("0.050000"),
        Decimal4.of("0.9999"),
        true));
  }

  @Test
  void representsAThroughDWithoutEverIssuingPointsAtRechargeTime() {
    RechargeBenefitResult a = RechargeBenefitCalculator.calculate(ineligible("user-a"));
    RechargeBenefitResult b = RechargeBenefitCalculator.calculate(eligible(
        "user-b", BenefitSnapshot.cashOnly("user-a"), "100", "1000", "0.1", "0"));
    RechargeBenefitResult c = RechargeBenefitCalculator.calculate(eligible(
        "user-c", BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "100", "1000", "0.03", "0.05"));
    RechargeBenefitResult d = RechargeBenefitCalculator.calculate(eligible(
        "user-d", BenefitSnapshot.pointsOnly("user-c"), "100", "1000", "0", "0.05"));

    assertThat(a.commissionFreeze()).isEmpty();
    assertThat(a.fundingBenefitSnapshot().pointBeneficiaryUserId()).isNull();
    assertThat(a.fundingBenefitSnapshot().cashPromoterUserId()).isNull();
    assertThat(a.fundingBenefitSnapshot().cashTier()).isEqualTo(CashTier.NONE);
    assertThat(b.commissionFreeze().orElseThrow().beneficiaryPromoterUserId()).isEqualTo("user-a");
    assertThat(b.commissionFreeze().orElseThrow().cashTier()).isEqualTo(CashTier.LEVEL_1);
    assertThat(b.fundingBenefitSnapshot().pointBeneficiaryUserId()).isNull();
    assertThat(c.commissionFreeze().orElseThrow().cashTier()).isEqualTo(CashTier.LEVEL_2);
    assertThat(c.fundingBenefitSnapshot().pointBeneficiaryUserId()).isEqualTo("user-b");
    assertThat(d.commissionFreeze()).isEmpty();
    assertThat(d.fundingBenefitSnapshot().pointBeneficiaryUserId()).isEqualTo("user-c");
    assertThat(Arrays.stream(RechargeBenefitResult.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly("fundingBenefitSnapshot", "commissionFreeze")
        .noneMatch(name -> name.toLowerCase().contains("pointreward"));
  }

  @Test
  void zeroRatesPreserveEligibleFrozenIdentitiesWithoutCreatingCash() {
    RechargeBenefitResult result = RechargeBenefitCalculator.calculate(eligible(
        "user-c",
        BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "100",
        "1000",
        "0",
        "0"));

    assertThat(result.commissionFreeze()).isEmpty();
    assertThat(result.fundingBenefitSnapshot().pointBeneficiaryUserId()).isEqualTo("user-b");
    assertThat(result.fundingBenefitSnapshot().cashPromoterUserId()).isEqualTo("user-a");
    assertThat(result.fundingBenefitSnapshot().cashRate6()).isEqualTo(Rate6.ZERO);
    assertThat(result.fundingBenefitSnapshot().pointRate6()).isEqualTo(Rate6.ZERO);
    assertThat(result.fundingBenefitSnapshot().frozenCommission4()).isEqualTo(Decimal4.ZERO);
  }

  @Test
  void ineligibleRechargeReturnsAnExplicitNonBenefitFundingSnapshot() {
    RechargeBenefitResult result = RechargeBenefitCalculator.calculate(ineligible("user-a"));

    assertThat(result.commissionFreeze()).isEmpty();
    assertThat(result.fundingBenefitSnapshot()).isEqualTo(new FundingBenefitSnapshot(
        "order-1",
        "lot-1",
        "user-a",
        ORDER_CREATED_AT,
        null,
        null,
        null,
        CashTier.NONE,
        Rate6.ZERO,
        Rate6.ZERO,
        Decimal4.ZERO,
        false));
  }

  @Test
  void rejectsNoneFundingSnapshotWithCashPromoterEvenWhenItMatchesWalletOwner() {
    assertThatThrownBy(() -> new FundingBenefitSnapshot(
        "order-1",
        "lot-1",
        "user-a",
        ORDER_CREATED_AT,
        null,
        null,
        "user-a",
        CashTier.NONE,
        Rate6.ZERO,
        Rate6.ZERO,
        Decimal4.ZERO,
        false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NONE");
  }

  @Test
  void outputDoesNotDriftWhenCurrentRelationshipsAndRatesChange() {
    BenefitSnapshot frozenBenefits = BenefitSnapshot.pointsAndCash("user-b", "user-a");
    Rate6 frozenCashRate = Rate6.of("0.03");
    Rate6 frozenPointRate = Rate6.of("0.05");
    RechargeBenefitInput input = new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-c", ORDER_CREATED_AT, 7L,
        frozenBenefits, Decimal4.of("100"), Decimal4.of("1000"),
        frozenCashRate, frozenPointRate, true);

    frozenBenefits = BenefitSnapshot.cashOnly("user-b");
    frozenCashRate = Rate6.of("0.9");
    frozenPointRate = Rate6.ZERO;
    RechargeBenefitResult result = RechargeBenefitCalculator.calculate(input);

    assertThat(frozenBenefits).isNotEqualTo(input.benefitSnapshot());
    assertThat(frozenCashRate).isNotEqualTo(input.cashRate6());
    assertThat(frozenPointRate).isNotEqualTo(input.pointRate6());
    assertThat(result.fundingBenefitSnapshot().cashPromoterUserId()).isEqualTo("user-a");
    assertThat(result.fundingBenefitSnapshot().pointBeneficiaryUserId()).isEqualTo("user-b");
    assertThat(result.fundingBenefitSnapshot().cashRate6()).isEqualTo(Rate6.of("0.03"));
    assertThat(result.fundingBenefitSnapshot().pointRate6()).isEqualTo(Rate6.of("0.05"));
    assertThat(RechargeBenefitCalculator.calculate(input)).isEqualTo(result);
  }

  @Test
  void rejectsRatesThatDoNotMatchFrozenIdentities() {
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
        "user-b", BenefitSnapshot.cashOnly("user-a"), "100", "1000", "0.1", "0.1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pointRate6");
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
        "user-d", BenefitSnapshot.pointsOnly("user-c"), "100", "1000", "0.1", "0.1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cashRate6");
  }

  @Test
  void rejectsIneligibleInputsUnlessTheyAreTrueNoneSnapshots() {
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-b", ORDER_CREATED_AT, null,
        BenefitSnapshot.cashOnly("user-a"), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.ZERO, Rate6.ZERO, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ineligible");
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-a", ORDER_CREATED_AT, null,
        BenefitSnapshot.none(), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.of("0.1"), Rate6.ZERO, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ineligible");
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-a", ORDER_CREATED_AT, 7L,
        BenefitSnapshot.none(), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.ZERO, Rate6.ZERO, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ineligible");
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-a", ORDER_CREATED_AT, 7L,
        BenefitSnapshot.none(), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.ZERO, Rate6.ZERO, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eligible");
  }

  @Test
  void rejectsInvalidSourceFactsAmountsVersionsAndSelfBenefits() {
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        " ", "order-1", "lot-1", "user-b", ORDER_CREATED_AT, 7L,
        BenefitSnapshot.cashOnly("user-a"), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.of("0.1"), Rate6.ZERO, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
        "event-1", "order-1", "lot-1", "user-b", null, 7L,
        BenefitSnapshot.cashOnly("user-a"), Decimal4.of("100"), Decimal4.of("1000"),
        Rate6.of("0.1"), Rate6.ZERO, true)))
        .isInstanceOf(NullPointerException.class);
    for (String paid : List.of("0", "-0.0001")) {
      assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
          "user-b", BenefitSnapshot.cashOnly("user-a"), paid, "1000", "0.1", "0")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("paidAmount4");
    }
    for (String credited : List.of("0", "-0.0001")) {
      assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
          "user-b", BenefitSnapshot.cashOnly("user-a"), "100", credited, "0.1", "0")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("creditedPoints4");
    }
    for (Long version : Arrays.asList(null, 0L, -1L)) {
      assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(new RechargeBenefitInput(
          "event-1", "order-1", "lot-1", "user-b", ORDER_CREATED_AT, version,
          BenefitSnapshot.cashOnly("user-a"), Decimal4.of("100"), Decimal4.of("1000"),
          Rate6.of("0.1"), Rate6.ZERO, true)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("referralBindingVersion");
    }
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
        "user-b", BenefitSnapshot.cashOnly("user-b"), "100", "1000", "0.1", "0")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
    assertThatThrownBy(() -> RechargeBenefitCalculator.calculate(eligible(
        "user-b", BenefitSnapshot.pointsOnly("user-b"), "100", "1000", "0", "0.1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
  }

  @Test
  void publicApiAcceptsOnlyTheFrozenRechargeFacts() {
    assertThat(RechargeBenefitInput.class.isRecord()).isTrue();
    assertThat(FundingBenefitSnapshot.class.isRecord()).isTrue();
    assertThat(CommissionFreezeAction.class.isRecord()).isTrue();
    assertThat(RechargeBenefitResult.class.isRecord()).isTrue();

    assertThat(Arrays.stream(RechargeBenefitInput.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly(
            "sourceEventId",
            "orderId",
            "fundingLotId",
            "walletOwnerUserId",
            "orderCreatedAt",
            "referralBindingVersion",
            "benefitSnapshot",
            "paidAmount4",
            "creditedPoints4",
            "cashRate6",
            "pointRate6",
            "benefitEligible")
        .noneMatch(name -> name.toLowerCase().contains("current")
            || name.toLowerCase().contains("fundinglottype"));
    assertThat(RechargeBenefitCalculator.class.getDeclaredMethods())
        .filteredOn(method -> method.getName().equals("calculate"))
        .singleElement()
        .satisfies(method -> assertThat(method.getParameterTypes())
            .containsExactly(RechargeBenefitInput.class));
  }

  private static RechargeBenefitInput eligible(
      String walletOwnerUserId,
      BenefitSnapshot benefitSnapshot,
      String paidAmount4,
      String creditedPoints4,
      String cashRate6,
      String pointRate6) {
    return new RechargeBenefitInput(
        "event-1",
        "order-1",
        "lot-1",
        walletOwnerUserId,
        ORDER_CREATED_AT,
        7L,
        benefitSnapshot,
        Decimal4.of(paidAmount4),
        Decimal4.of(creditedPoints4),
        Rate6.of(cashRate6),
        Rate6.of(pointRate6),
        true);
  }

  private static RechargeBenefitInput ineligible(String walletOwnerUserId) {
    return new RechargeBenefitInput(
        "event-1",
        "order-1",
        "lot-1",
        walletOwnerUserId,
        ORDER_CREATED_AT,
        null,
        BenefitSnapshot.none(),
        Decimal4.of("100"),
        Decimal4.of("1000"),
        Rate6.ZERO,
        Rate6.ZERO,
        false);
  }
}
