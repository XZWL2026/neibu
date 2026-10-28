package com.xzwl.cloud.promotion.domain.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuccessfulBenefitCalculatorTest {

  @Test
  void calculatesLevelTwoPointsAndCashFromTheFrozenAllocationSnapshot() {
    SuccessfulBenefitResult result = SuccessfulBenefitCalculator.calculate(
        input(
            BenefitSnapshot.pointsAndCash("user-b", "user-a"),
            "0.050000",
            "100.0000",
            "33.3333",
            "10.0000",
            "0.0000",
            false,
            true));

    assertThat(result.sourceSettlementId()).isEqualTo("settlement-1");
    assertThat(result.sourceAllocationId()).isEqualTo("allocation-1");
    assertThat(result.sourceFundingLotId()).isEqualTo("lot-1");
    assertThat(result.sourceWalletOwnerUserId()).isEqualTo("user-c");
    assertThat(result.referralBindingVersion()).isEqualTo(7);
    assertThat(result.pointReward())
        .contains(new PointRewardAction("user-b", Decimal4.of("1.6666")));
    assertThat(result.cashRelease())
        .contains(new CashReleaseAction("user-a", CashTier.LEVEL_2, Decimal4.of("3.3333")));
  }

  @Test
  void calculatesTheFixedAThroughDBenefitShapes() {
    SuccessfulBenefitResult a = SuccessfulBenefitCalculator.calculate(inputFor(
        "user-a", BenefitSnapshot.none(),
        "0", "100", "50", "0", "0", false, true));
    SuccessfulBenefitResult b = SuccessfulBenefitCalculator.calculate(inputFor(
        "user-b",
        BenefitSnapshot.cashOnly("user-a"), "0", "100", "50", "8", "0", false, true));
    SuccessfulBenefitResult c = SuccessfulBenefitCalculator.calculate(inputFor(
        "user-c",
        BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "0.1", "100", "50", "4", "0", false, true));
    SuccessfulBenefitResult d = SuccessfulBenefitCalculator.calculate(inputFor(
        "user-d",
        BenefitSnapshot.pointsOnly("user-c"), "0.1", "100", "50", "0", "0", false, true));

    assertThat(a.pointReward()).isEmpty();
    assertThat(a.cashRelease()).isEmpty();
    assertThat(b.pointReward()).isEmpty();
    assertThat(b.cashRelease())
        .contains(new CashReleaseAction("user-a", CashTier.LEVEL_1, Decimal4.of("4")));
    assertThat(c.pointReward())
        .contains(new PointRewardAction("user-b", Decimal4.of("5")));
    assertThat(c.cashRelease())
        .contains(new CashReleaseAction("user-a", CashTier.LEVEL_2, Decimal4.of("2")));
    assertThat(d.pointReward())
        .contains(new PointRewardAction("user-c", Decimal4.of("5")));
    assertThat(d.cashRelease()).isEmpty();
  }

  @Test
  void releasesTheExactRemainingCashOnFinalDepletion() {
    SuccessfulBenefitResult result = SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.cashOnly("user-a"),
        "0", "100", "33.3333", "10", "6.6666", true, true));

    assertThat(result.cashRelease())
        .contains(new CashReleaseAction("user-a", CashTier.LEVEL_1, Decimal4.of("3.3334")));
  }

  @Test
  void refusesANonFinalReleaseThatExceedsTheFrozenRemainder() {
    SuccessfulBenefitInput input = input(
        BenefitSnapshot.cashOnly("user-a"),
        "0", "10", "5", "10", "9", false, true);

    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remaining");
  }

  @Test
  void zeroRatesKeepTheFrozenIdentitiesButProduceNoActions() {
    SuccessfulBenefitResult result = SuccessfulBenefitCalculator.calculate(inputFor(
        "user-c",
        BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "0", "100", "50", "0", "0", false, true));

    assertThat(result.pointReward()).isEmpty();
    assertThat(result.cashRelease()).isEmpty();
  }

  @Test
  void ineligibleFundingIncludingReferralRewardsProducesNoActions() {
    SuccessfulBenefitResult result = SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.pointsAndCash("user-b", "user-a"),
        "0.1", "100", "50", "10", "0", false, false));

    assertThat(result.pointReward()).isEmpty();
    assertThat(result.cashRelease()).isEmpty();
  }

  @Test
  void rejectsPointRateWithoutAFrozenPointBeneficiary() {
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.cashOnly("user-a"), "0.1", "100", "50", "10", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pointRate6");
  }

  @Test
  void rejectsCashAmountsWithoutAFrozenCashPromoter() {
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-d",
        BenefitSnapshot.pointsOnly("user-c"), "0.1", "100", "50", "1", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cash promoter");
  }

  @Test
  void rejectsAFrozenBenefitThatPaysTheConsumingWalletOwner() {
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-b",
        BenefitSnapshot.pointsOnly("user-b"),
        "0.1", "100", "50", "0", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-a",
        BenefitSnapshot.cashOnly("user-a"),
        "0", "100", "50", "10", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
  }

  @Test
  void rejectsIllegalAmountsVersionsAndBlankSourceIds() {
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-d", BenefitSnapshot.pointsOnly("user-c"),
        "0.1", "0", "1", "0", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-d", BenefitSnapshot.pointsOnly("user-c"),
        "0.1", "10", "0", "0", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(inputFor(
        "user-d", BenefitSnapshot.pointsOnly("user-c"),
        "0.1", "10", "11", "0", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.cashOnly("user-a"), "0", "10", "1", "-1", "0", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.cashOnly("user-a"), "0", "10", "1", "1", "-1", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(input(
        BenefitSnapshot.cashOnly("user-a"), "0", "10", "1", "1", "2", false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(new SuccessfulBenefitInput(
        " ", "allocation-1", "lot-1", "user-c", 7,
        BenefitSnapshot.pointsOnly("user-c"), Rate6.of("0.1"),
        Decimal4.of("10"), Decimal4.of("1"), Decimal4.ZERO, Decimal4.ZERO,
        false, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SuccessfulBenefitCalculator.calculate(new SuccessfulBenefitInput(
        "settlement-1", "allocation-1", "lot-1", "user-c", 0,
        BenefitSnapshot.pointsOnly("user-c"), Rate6.of("0.1"),
        Decimal4.of("10"), Decimal4.of("1"), Decimal4.ZERO, Decimal4.ZERO,
        false, true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void publicApiAcceptsOnlyTheFrozenSnapshotAndNoCurrentPolicyOrBinding() {
    assertThat(SuccessfulBenefitInput.class.isRecord()).isTrue();
    assertThat(SuccessfulBenefitResult.class.isRecord()).isTrue();
    assertThat(PointRewardAction.class.isRecord()).isTrue();
    assertThat(CashReleaseAction.class.isRecord()).isTrue();

    List<String> inputComponents = Arrays.stream(SuccessfulBenefitInput.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    assertThat(inputComponents).containsExactly(
        "sourceSettlementId",
        "sourceAllocationId",
        "sourceFundingLotId",
        "sourceWalletOwnerUserId",
        "referralBindingVersion",
        "benefitSnapshot",
        "pointRate6",
        "lotOriginalPoints4",
        "successfulPoints4",
        "frozenCommission4",
        "releasedCommissionBefore4",
        "finalDepletion",
        "benefitEligible");
    assertThat(inputComponents)
        .noneMatch(name -> name.toLowerCase().contains("current") || name.toLowerCase().contains("policy"));

    assertThat(SuccessfulBenefitCalculator.class.getDeclaredMethods())
        .filteredOn(method -> method.getName().equals("calculate"))
        .singleElement()
        .satisfies(method -> {
          assertThat(method.getParameterTypes()).containsExactly(SuccessfulBenefitInput.class);
          assertThat(method.getGenericParameterTypes()).allMatch(type -> noLivePolicyType(type));
        });
  }

  private static boolean noLivePolicyType(Type type) {
    String name = type.getTypeName().toLowerCase();
    return !name.contains("policy") && !name.contains("referralbinding");
  }

  private static SuccessfulBenefitInput input(
      BenefitSnapshot benefitSnapshot,
      String pointRate6,
      String lotOriginalPoints4,
      String successfulPoints4,
      String frozenCommission4,
      String releasedCommissionBefore4,
      boolean finalDepletion,
      boolean benefitEligible) {
    return inputFor(
        "user-c",
        benefitSnapshot,
        pointRate6,
        lotOriginalPoints4,
        successfulPoints4,
        frozenCommission4,
        releasedCommissionBefore4,
        finalDepletion,
        benefitEligible);
  }

  private static SuccessfulBenefitInput inputFor(
      String sourceWalletOwnerUserId,
      BenefitSnapshot benefitSnapshot,
      String pointRate6,
      String lotOriginalPoints4,
      String successfulPoints4,
      String frozenCommission4,
      String releasedCommissionBefore4,
      boolean finalDepletion,
      boolean benefitEligible) {
    return new SuccessfulBenefitInput(
        "settlement-1",
        "allocation-1",
        "lot-1",
        sourceWalletOwnerUserId,
        7,
        benefitSnapshot,
        Rate6.of(pointRate6),
        Decimal4.of(lotOriginalPoints4),
        Decimal4.of(successfulPoints4),
        Decimal4.of(frozenCommission4),
        Decimal4.of(releasedCommissionBefore4),
        finalDepletion,
        benefitEligible);
  }
}
