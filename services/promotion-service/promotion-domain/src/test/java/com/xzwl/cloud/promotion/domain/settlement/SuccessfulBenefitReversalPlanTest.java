package com.xzwl.cloud.promotion.domain.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SuccessfulBenefitReversalPlanTest {

  @Test
  void copiesEveryFrozenSourceAndDualBenefitFieldExactly() {
    SuccessfulBenefitResult successful = result(
        Optional.of(new PointRewardAction("point-user", Decimal4.of("1.2345"))),
        Optional.of(new CashReleaseAction(
            "cash-user", CashTier.LEVEL_2, Decimal4.of("6.7890"))));

    SuccessfulBenefitReversalPlan reversal =
        SuccessfulBenefitReversalPlan.from(successful);

    assertThat(reversal.sourceSettlementId()).isEqualTo("settlement-original");
    assertThat(reversal.sourceAllocationId()).isEqualTo("allocation-original");
    assertThat(reversal.sourceFundingLotId()).isEqualTo("lot-original");
    assertThat(reversal.sourceWalletOwnerUserId()).isEqualTo("wallet-owner-original");
    assertThat(reversal.referralBindingVersion()).isEqualTo(37);
    assertThat(reversal.pointRewardReversal()).contains(
        pointReversal("point-user", "1.2345"));
    assertThat(reversal.cashReleaseReversal()).contains(
        cashReversal("cash-user", CashTier.LEVEL_2, "6.7890"));
  }

  @Test
  void createsOnlyTheReversalsPresentInTheSuccessfulResult() {
    SuccessfulBenefitReversalPlan cashOnly = SuccessfulBenefitReversalPlan.from(result(
        Optional.empty(),
        Optional.of(new CashReleaseAction(
            "cash-user", CashTier.LEVEL_1, Decimal4.of("2.0001")))));
    SuccessfulBenefitReversalPlan pointsOnly = SuccessfulBenefitReversalPlan.from(result(
        Optional.of(new PointRewardAction("point-user", Decimal4.of("3.0002"))),
        Optional.empty()));
    SuccessfulBenefitReversalPlan empty = SuccessfulBenefitReversalPlan.from(
        result(Optional.empty(), Optional.empty()));

    assertThat(cashOnly.pointRewardReversal()).isEmpty();
    assertThat(cashOnly.cashReleaseReversal()).contains(
        cashReversal("cash-user", CashTier.LEVEL_1, "2.0001"));
    assertThat(pointsOnly.pointRewardReversal()).contains(
        pointReversal("point-user", "3.0002"));
    assertThat(pointsOnly.cashReleaseReversal()).isEmpty();
    assertThat(empty.pointRewardReversal()).isEmpty();
    assertThat(empty.cashReleaseReversal()).isEmpty();
  }

  @Test
  void exposesOnlyImmutableFrozenResultInputsAndDecimalAmounts() throws Exception {
    assertThat(SuccessfulBenefitReversalPlan.class.isRecord()).isTrue();
    assertThat(PointRewardReversalAction.class.isRecord()).isTrue();
    assertThat(CashReleaseReversalAction.class.isRecord()).isTrue();
    assertThat(SuccessfulBenefitReversalPlan.class.getMethod(
            "from", SuccessfulBenefitResult.class).getParameterTypes())
        .containsExactly(SuccessfulBenefitResult.class);

    assertThat(Arrays.stream(SuccessfulBenefitReversalPlan.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly(
            "sourceSettlementId",
            "sourceAllocationId",
            "sourceFundingLotId",
            "sourceWalletOwnerUserId",
            "referralBindingVersion",
            "pointRewardReversal",
            "cashReleaseReversal")
        .noneMatch(name -> name.toLowerCase().contains("current")
            || name.toLowerCase().contains("policy")
            || name.toLowerCase().contains("rate"));
    assertThat(Arrays.stream(PointRewardReversalAction.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly(
            "sourceSettlementId",
            "sourceAllocationId",
            "sourceFundingLotId",
            "sourceWalletOwnerUserId",
            "referralBindingVersion",
            "beneficiaryUserId",
            "points4");
    assertThat(Arrays.stream(CashReleaseReversalAction.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly(
            "sourceSettlementId",
            "sourceAllocationId",
            "sourceFundingLotId",
            "sourceWalletOwnerUserId",
            "referralBindingVersion",
            "beneficiaryPromoterUserId",
            "cashTier",
            "amount4");
  }

  @Test
  void rejectsNullOrInvalidPlansAndActions() {
    assertThatThrownBy(() -> SuccessfulBenefitReversalPlan.from(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("successfulBenefitResult");
    assertThatThrownBy(() -> new SuccessfulBenefitReversalPlan(
        " ", "allocation", "lot", "wallet", 1, Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceSettlementId");
    assertThatThrownBy(() -> new SuccessfulBenefitReversalPlan(
        "settlement", "allocation", "lot", "wallet", 0,
        Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referralBindingVersion");
    assertThatThrownBy(() -> new SuccessfulBenefitReversalPlan(
        "settlement", "allocation", "lot", "wallet", 1, null, Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("pointRewardReversal");
    assertThatThrownBy(() -> pointReversal(" ", "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneficiaryUserId");
    assertThatThrownBy(() -> pointReversal("point-user", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("points4");
    assertThatThrownBy(() -> cashReversal("cash-user", CashTier.NONE, "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cash tier");
    assertThatThrownBy(() -> pointReversal("wallet-owner-original", "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
    assertThatThrownBy(() -> cashReversal(
        "wallet-owner-original", CashTier.LEVEL_1, "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
    assertThatThrownBy(() -> new CashReleaseReversalAction(
        "settlement-original", "allocation-original", "lot-original",
        "wallet-owner-original", 37, "cash-user", CashTier.LEVEL_1, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("amount4");
    assertThatThrownBy(() -> new SuccessfulBenefitReversalPlan(
        "settlement-original",
        "allocation-original",
        "lot-original",
        "wallet-owner-original",
        37,
        Optional.of(new PointRewardReversalAction(
            "different-settlement", "allocation-original", "lot-original",
            "wallet-owner-original", 37, "point-user", Decimal4.of("1"))),
        Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source");
  }

  @Test
  void successfulResultBoundaryRejectsSelfPointAndCashBenefits() {
    assertThatThrownBy(() -> result(
        Optional.of(new PointRewardAction(
            "wallet-owner-original", Decimal4.of("1"))),
        Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
    assertThatThrownBy(() -> result(
        Optional.empty(),
        Optional.of(new CashReleaseAction(
            "wallet-owner-original", CashTier.LEVEL_1, Decimal4.of("1")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallet owner");
  }

  @Test
  void everyLegalSuccessfulResultShapeCanBePlannedForReversal() {
    List<SuccessfulBenefitResult> legalResults = List.of(
        result(
            Optional.of(new PointRewardAction("point-user", Decimal4.of("1"))),
            Optional.of(new CashReleaseAction(
                "cash-user", CashTier.LEVEL_2, Decimal4.of("2")))),
        result(
            Optional.empty(),
            Optional.of(new CashReleaseAction(
                "cash-user", CashTier.LEVEL_1, Decimal4.of("2")))),
        result(
            Optional.of(new PointRewardAction("point-user", Decimal4.of("1"))),
            Optional.empty()),
        result(Optional.empty(), Optional.empty()));

    assertThat(legalResults).allSatisfy(successful ->
        assertThatCode(() -> SuccessfulBenefitReversalPlan.from(successful))
            .doesNotThrowAnyException());
  }

  private static PointRewardReversalAction pointReversal(
      String beneficiaryUserId, String points4) {
    return new PointRewardReversalAction(
        "settlement-original",
        "allocation-original",
        "lot-original",
        "wallet-owner-original",
        37,
        beneficiaryUserId,
        Decimal4.of(points4));
  }

  private static CashReleaseReversalAction cashReversal(
      String beneficiaryPromoterUserId, CashTier cashTier, String amount4) {
    return new CashReleaseReversalAction(
        "settlement-original",
        "allocation-original",
        "lot-original",
        "wallet-owner-original",
        37,
        beneficiaryPromoterUserId,
        cashTier,
        Decimal4.of(amount4));
  }

  private static SuccessfulBenefitResult result(
      Optional<PointRewardAction> pointReward,
      Optional<CashReleaseAction> cashRelease) {
    return new SuccessfulBenefitResult(
        "settlement-original",
        "allocation-original",
        "lot-original",
        "wallet-owner-original",
        37,
        pointReward,
        cashRelease);
  }
}
