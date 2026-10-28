package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.lang.reflect.Executable;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

public final class ReferralRewardAccountingTest {

  private static final Instant CREDITED_AT = Instant.parse("2026-09-04T00:00:00Z");
  private static int passed;

  public static void main(String[] args) {
    rewardLotsAreImmutableAndNeverBenefitEligible();
    reversalUsesTheOriginalEntitlementAndCreatesDebtForTheShortfall();
    newReferralRewardOffsetsDebtBeforeCreatingARewardLot();
    rewardSmallerThanDebtIsFullyOffsetWithoutCreatingALot();
    invalidLotsDebtsAndAmountsAreRejected();
    crossUserOperationsAreRejected();
    publicAccountingApiCannotAcceptArbitraryFundingLotTypes();
    System.out.println("ReferralRewardAccountingTest passed: " + passed);
  }

  private static void rewardLotsAreImmutableAndNeverBenefitEligible() {
    ReferralRewardLot lot = rewardLot("entitlement-1", "user-1", "lot-1", "5", "3");

    check(lot.rewardEntitlementId().equals("entitlement-1"), "entitlement id");
    check(lot.beneficiaryUserId().equals("user-1"), "beneficiary");
    check(lot.lotId().equals("lot-1"), "lot id");
    check(lot.amount4().equals(Decimal4.of("5")), "original reward");
    check(lot.remaining4().equals(Decimal4.of("3")), "remaining reward");
    check(lot.creditedAt().equals(CREDITED_AT), "credited time");
    check(lot.fundingLotType() == FundingLotType.REFERRAL_REWARD, "reward funding type");
    check(!lot.benefitEligible(), "reward lot cannot generate referral benefits");
    passed++;
  }

  private static void reversalUsesTheOriginalEntitlementAndCreatesDebtForTheShortfall() {
    ReferralRewardLot original = rewardLot("entitlement-1", "user-1", "lot-1", "5", "3");
    ReferralPointDebt originalDebt = ReferralPointDebt.zero("user-1");

    ReferralRewardReversalResult result =
        ReferralRewardAccounting.reverse(original, originalDebt, Decimal4.of("5"));

    check(result.recovered4().equals(Decimal4.of("3")), "recover current lot balance");
    check(result.createdDebt4().equals(Decimal4.of("2")), "create shortfall debt");
    check(result.updatedLot().remaining4().equals(Decimal4.zero()), "lot reaches zero");
    check(result.updatedDebt().amount4().equals(Decimal4.of("2")), "debt reaches two");
    check(original.remaining4().equals(Decimal4.of("3")), "input lot remains unchanged");
    check(originalDebt.amount4().equals(Decimal4.zero()), "input debt remains unchanged");
    check(result.updatedLot().rewardEntitlementId().equals(original.rewardEntitlementId()), "original entitlement retained");
    passed++;
  }

  private static void newReferralRewardOffsetsDebtBeforeCreatingARewardLot() {
    ReferralPointDebt debt = new ReferralPointDebt("user-1", Decimal4.of("2"));

    ReferralRewardCreditResult result =
        ReferralRewardAccounting.creditReferralReward(
            "entitlement-2", "user-1", "lot-2", Decimal4.of("4"), CREDITED_AT, debt);

    check(result.offsetDebt4().equals(Decimal4.of("2")), "offset existing debt");
    check(result.creditableReward4().equals(Decimal4.of("2")), "only remainder creditable");
    check(result.updatedDebt().amount4().equals(Decimal4.zero()), "debt cleared");
    ReferralRewardLot created = result.createdLot().orElseThrow();
    check(created.amount4().equals(Decimal4.of("2")), "new lot original equals credited remainder");
    check(created.remaining4().equals(Decimal4.of("2")), "new lot remaining equals credited remainder");
    check(created.fundingLotType() == FundingLotType.REFERRAL_REWARD, "created reward type");
    check(!created.benefitEligible(), "created reward remains ineligible");
    check(debt.amount4().equals(Decimal4.of("2")), "input debt remains unchanged");
    passed++;
  }

  private static void rewardSmallerThanDebtIsFullyOffsetWithoutCreatingALot() {
    ReferralRewardCreditResult result =
        ReferralRewardAccounting.creditReferralReward(
            "entitlement-3",
            "user-1",
            "lot-3",
            Decimal4.of("1.5"),
            CREDITED_AT,
            new ReferralPointDebt("user-1", Decimal4.of("2")));

    check(result.offsetDebt4().equals(Decimal4.of("1.5")), "entire reward offsets debt");
    check(result.creditableReward4().equals(Decimal4.zero()), "nothing is creditable");
    check(result.updatedDebt().amount4().equals(Decimal4.of("0.5")), "remaining debt");
    check(result.createdLot().isEmpty(), "no usable lot is created");
    passed++;
  }

  private static void invalidLotsDebtsAndAmountsAreRejected() {
    expectThrows(IllegalArgumentException.class, () -> rewardLot(" ", "user-1", "lot-1", "5", "3"));
    expectThrows(IllegalArgumentException.class, () -> rewardLot("entitlement-1", " ", "lot-1", "5", "3"));
    expectThrows(IllegalArgumentException.class, () -> rewardLot("entitlement-1", "user-1", " ", "5", "3"));
    expectThrows(IllegalArgumentException.class, () -> rewardLot("entitlement-1", "user-1", "lot-1", "0", "0"));
    expectThrows(IllegalArgumentException.class, () -> rewardLot("entitlement-1", "user-1", "lot-1", "5", "-1"));
    expectThrows(IllegalArgumentException.class, () -> rewardLot("entitlement-1", "user-1", "lot-1", "5", "6"));
    expectThrows(IllegalArgumentException.class, () -> new ReferralPointDebt("user-1", Decimal4.of("-1")));

    ReferralRewardLot lot = rewardLot("entitlement-1", "user-1", "lot-1", "5", "3");
    ReferralPointDebt debt = ReferralPointDebt.zero("user-1");
    expectThrows(IllegalArgumentException.class, () -> ReferralRewardAccounting.reverse(lot, debt, Decimal4.zero()));
    expectThrows(IllegalArgumentException.class, () -> ReferralRewardAccounting.reverse(lot, debt, Decimal4.of("-1")));
    expectThrows(IllegalArgumentException.class, () -> ReferralRewardAccounting.reverse(lot, debt, Decimal4.of("5.0001")));
    expectThrows(
        IllegalArgumentException.class,
        () -> ReferralRewardAccounting.creditReferralReward("entitlement-2", "user-1", "lot-2", Decimal4.zero(), CREDITED_AT, debt));
    passed++;
  }

  private static void crossUserOperationsAreRejected() {
    ReferralRewardLot lot = rewardLot("entitlement-1", "user-1", "lot-1", "5", "3");
    ReferralPointDebt otherUserDebt = ReferralPointDebt.zero("user-2");

    expectThrows(
        IllegalArgumentException.class,
        () -> ReferralRewardAccounting.reverse(lot, otherUserDebt, Decimal4.of("1")));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            ReferralRewardAccounting.creditReferralReward(
                "entitlement-2", "user-1", "lot-2", Decimal4.of("1"), CREDITED_AT, otherUserDebt));
    passed++;
  }

  private static void publicAccountingApiCannotAcceptArbitraryFundingLotTypes() {
    Stream<Executable> api =
        Stream.concat(
            Arrays.stream(ReferralRewardAccounting.class.getConstructors()),
            Arrays.stream(ReferralRewardAccounting.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())));
    check(
        api.flatMap(executable -> Arrays.stream(executable.getParameterTypes()))
            .noneMatch(type -> type == FundingLotType.class),
        "accounting API cannot accept a funding lot type");
    passed++;
  }

  private static ReferralRewardLot rewardLot(
      String entitlementId,
      String beneficiaryUserId,
      String lotId,
      String amount,
      String remaining) {
    return new ReferralRewardLot(
        entitlementId,
        beneficiaryUserId,
        lotId,
        Decimal4.of(amount),
        Decimal4.of(remaining),
        CREDITED_AT);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) return type.cast(thrown);
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }
}
