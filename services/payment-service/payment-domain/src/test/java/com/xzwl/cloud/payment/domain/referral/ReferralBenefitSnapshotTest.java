package com.xzwl.cloud.payment.domain.referral;

import java.lang.reflect.Executable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

public final class ReferralBenefitSnapshotTest {

  private static final Instant ORDER_CREATED_AT = Instant.parse("2026-09-04T00:00:00Z");
  private static int passed;

  public static void main(String[] args) {
    pendingRetainsTheOriginalOrderRecoveryFacts();
    resolutionIsImmutableAndDoesNotUseWorkerTime();
    resolvedAndNoneAreTerminalAndIdempotentForTheSameSemantics();
    conflictingTerminalResultsCannotOverwriteTheSnapshot();
    expectedVersionProtectsPendingResolution();
    fieldCombinationsAndRatesAreValidated();
    zeroRatesCannotRetainBenefitIdentities();
    identitiesAndTimeAreValidatedWithoutLeakingValues();
    publicApiNeverAcceptsFloatingPointRates();
    System.out.println("ReferralBenefitSnapshotTest passed: " + passed);
  }

  private static void pendingRetainsTheOriginalOrderRecoveryFacts() {
    ReferralBenefitSnapshot pending = pending();

    check(pending.resolution() == BenefitResolution.PENDING, "pending resolution");
    check(pending.orderId().equals("order-1"), "order identity");
    check(pending.orderCreatedAt().equals(ORDER_CREATED_AT), "original order time");
    check(pending.benefitLookupUserId().equals("payer-user-1"), "recovery user identity");
    check(pending.version() == 7, "pending version");
    check(pending.referralBindingVersion().equals(OptionalLong.empty()), "no guessed binding");
    passed++;
  }

  private static void resolutionIsImmutableAndDoesNotUseWorkerTime() {
    String pointBeneficiary = "point-user-1";
    String cashPromoter = "cash-promoter-1";
    BigDecimal pointRate = new BigDecimal("0.100000");
    BigDecimal cashRate = new BigDecimal("0.200000");
    ReferralBenefitSnapshot resolved =
        pending().resolve(
            7,
            19,
            pointBeneficiary,
            cashPromoter,
            CashTier.LEVEL_2,
            cashRate,
            pointRate);

    pointBeneficiary = "changed-point-user";
    cashPromoter = "changed-cash-promoter";
    pointRate = BigDecimal.ZERO;
    cashRate = BigDecimal.ONE;

    check(resolved.resolution() == BenefitResolution.RESOLVED, "resolved state");
    check(resolved.version() == 8, "version increment");
    check(resolved.orderCreatedAt().equals(ORDER_CREATED_AT), "order time remains fixed");
    check(resolved.referralBindingVersion().equals(OptionalLong.of(19)), "binding version fixed");
    check(resolved.pointBeneficiaryUserId().equals(Optional.of("point-user-1")), "point owner fixed");
    check(resolved.cashPromoterUserId().equals(Optional.of("cash-promoter-1")), "cash owner fixed");
    check(resolved.cashTier() == CashTier.LEVEL_2, "cash tier fixed");
    check(resolved.cashRate6().compareTo(new BigDecimal("0.2")) == 0, "cash rate fixed");
    check(resolved.pointRate6().compareTo(new BigDecimal("0.1")) == 0, "point rate fixed");
    check(
        Arrays.stream(ReferralBenefitSnapshot.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("resolve"))
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(type -> type == Instant.class),
        "resolution must not accept worker time");
    passed++;
  }

  private static void resolvedAndNoneAreTerminalAndIdempotentForTheSameSemantics() {
    ReferralBenefitSnapshot resolved = resolved();
    ReferralBenefitSnapshot none = pending().resolveNone(7);

    check(
        resolved.resolve(
                7,
                19,
                "point-user-1",
                "cash-promoter-1",
                CashTier.LEVEL_1,
                new BigDecimal("0.2"),
                new BigDecimal("0.1"))
            == resolved,
        "same resolved command returns original object");
    check(none.resolveNone(7) == none, "same none command returns original object");
    expectThrows(
        IllegalStateException.class,
        () ->
            resolved.resolve(
                -99,
                19,
                "point-user-1",
                "cash-promoter-1",
                CashTier.LEVEL_1,
                new BigDecimal("0.2"),
                new BigDecimal("0.1")));
    expectThrows(IllegalStateException.class, () -> none.resolveNone(-99));
    check(none.version() == 8, "none increments version");
    check(none.resolution() == BenefitResolution.NONE, "none state");
    check(none.referralBindingVersion().isEmpty(), "none has no binding");
    check(none.pointBeneficiaryUserId().isEmpty(), "none has no point beneficiary");
    check(none.cashPromoterUserId().isEmpty(), "none has no cash beneficiary");
    check(none.cashTier() == CashTier.NONE, "none cash tier");
    check(none.cashRate6().compareTo(BigDecimal.ZERO) == 0, "none cash rate");
    check(none.pointRate6().compareTo(BigDecimal.ZERO) == 0, "none point rate");
    passed++;
  }

  private static void conflictingTerminalResultsCannotOverwriteTheSnapshot() {
    ReferralBenefitSnapshot resolved = resolved();
    ReferralBenefitSnapshot none = pending().resolveNone(7);

    expectThrows(IllegalStateException.class, () -> resolved.resolveNone(7));
    expectThrows(
        IllegalStateException.class,
        () ->
            resolved.resolve(
                7,
                20,
                "point-user-1",
                "cash-promoter-1",
                CashTier.LEVEL_1,
                new BigDecimal("0.2"),
                new BigDecimal("0.1")));
    expectThrows(
        IllegalStateException.class,
        () ->
            none.resolve(
                7,
                19,
                "point-user-1",
                null,
                CashTier.NONE,
                BigDecimal.ZERO,
                new BigDecimal("0.1")));
    check(resolved.referralBindingVersion().equals(OptionalLong.of(19)), "resolved not overwritten");
    check(none.resolution() == BenefitResolution.NONE, "none not overwritten");
    passed++;
  }

  private static void expectedVersionProtectsPendingResolution() {
    ReferralBenefitSnapshot pending = pending();

    IllegalStateException resolvedMismatch =
        expectThrows(
            IllegalStateException.class,
            () ->
                pending.resolve(
                    6,
                    19,
                    "point-user-1",
                    null,
                    CashTier.NONE,
                    BigDecimal.ZERO,
                    new BigDecimal("0.1")));
    IllegalStateException noneMismatch =
        expectThrows(IllegalStateException.class, () -> pending.resolveNone(8));
    check(resolvedMismatch.getMessage().contains("expectedVersion"), "resolved version diagnostic");
    check(noneMismatch.getMessage().contains("expectedVersion"), "none version diagnostic");
    check(pending.resolution() == BenefitResolution.PENDING, "version failure keeps state");
    check(pending.version() == 7, "version failure keeps version");
    passed++;
  }

  private static void fieldCombinationsAndRatesAreValidated() {
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, null, null, CashTier.NONE, BigDecimal.ZERO, BigDecimal.ZERO));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, null, null, CashTier.NONE, BigDecimal.ZERO, new BigDecimal("0.1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, "point-user-1", "cash-promoter-1", CashTier.NONE, new BigDecimal("0.1"), new BigDecimal("0.1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, "point-user-1", null, CashTier.LEVEL_1, new BigDecimal("0.1"), new BigDecimal("0.1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, "point-user-1", null, CashTier.NONE, BigDecimal.ZERO, new BigDecimal("0.1234567")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, "point-user-1", null, CashTier.NONE, BigDecimal.ZERO, new BigDecimal("1.000001")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 19, "point-user-1", null, CashTier.NONE, BigDecimal.ZERO, new BigDecimal("-0.1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending().resolve(7, 0, "point-user-1", null, CashTier.NONE, BigDecimal.ZERO, new BigDecimal("0.1")));
    passed++;
  }

  private static void zeroRatesCannotRetainBenefitIdentities() {
    expectThrows(
        IllegalArgumentException.class,
        () ->
            pending()
                .resolve(
                    7,
                    19,
                    "point-user-1",
                    "cash-promoter-1",
                    CashTier.LEVEL_1,
                    new BigDecimal("0.1"),
                    BigDecimal.ZERO));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            pending()
                .resolve(
                    7,
                    19,
                    "point-user-1",
                    "cash-promoter-1",
                    CashTier.LEVEL_1,
                    BigDecimal.ZERO,
                    new BigDecimal("0.1")));
    passed++;
  }

  private static void identitiesAndTimeAreValidatedWithoutLeakingValues() {
    String secretOrderId = "secret-order-id";
    IllegalArgumentException blankOrder =
        expectThrows(
            IllegalArgumentException.class,
            () -> ReferralBenefitSnapshot.pending(" ", ORDER_CREATED_AT, "payer-user-1", 0));
    expectThrows(
        NullPointerException.class,
        () -> ReferralBenefitSnapshot.pending("order-1", null, "payer-user-1", 0));
    expectThrows(
        IllegalArgumentException.class,
        () -> ReferralBenefitSnapshot.pending("order-1", ORDER_CREATED_AT, " ", 0));
    ReferralBenefitSnapshot snapshot =
        ReferralBenefitSnapshot.pending(secretOrderId, ORDER_CREATED_AT, "secret-user-id", 0);

    check(!blankOrder.getMessage().contains(secretOrderId), "validation does not leak identity");
    check(!snapshot.toString().contains(secretOrderId), "toString hides order identity");
    check(!snapshot.toString().contains("secret-user-id"), "toString hides user identity");
    passed++;
  }

  private static void publicApiNeverAcceptsFloatingPointRates() {
    Stream<Executable> api =
        Stream.concat(
            Arrays.stream(ReferralBenefitSnapshot.class.getConstructors()),
            Arrays.stream(ReferralBenefitSnapshot.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())));
    check(
        api.flatMap(executable -> Arrays.stream(executable.getParameterTypes()))
            .noneMatch(type -> type == double.class || type == Double.class || type == float.class || type == Float.class),
        "floating point is forbidden");
    passed++;
  }

  private static ReferralBenefitSnapshot pending() {
    return ReferralBenefitSnapshot.pending("order-1", ORDER_CREATED_AT, "payer-user-1", 7);
  }

  private static ReferralBenefitSnapshot resolved() {
    return pending().resolve(
        7,
        19,
        "point-user-1",
        "cash-promoter-1",
        CashTier.LEVEL_1,
        new BigDecimal("0.2"),
        new BigDecimal("0.1"));
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) {
        return type.cast(thrown);
      }
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }
}
