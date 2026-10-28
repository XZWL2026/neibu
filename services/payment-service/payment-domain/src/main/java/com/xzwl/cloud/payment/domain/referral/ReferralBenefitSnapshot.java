package com.xzwl.cloud.payment.domain.referral;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class ReferralBenefitSnapshot {

  private static final int MAX_ID_LENGTH = 200;
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal ONE = BigDecimal.ONE;

  private final String orderId;
  private final Instant orderCreatedAt;
  private final String benefitLookupUserId;
  private final BenefitResolution resolution;
  private final long version;
  private final Long referralBindingVersion;
  private final String pointBeneficiaryUserId;
  private final String cashPromoterUserId;
  private final CashTier cashTier;
  private final BigDecimal cashRate6;
  private final BigDecimal pointRate6;

  private ReferralBenefitSnapshot(
      String orderId,
      Instant orderCreatedAt,
      String benefitLookupUserId,
      BenefitResolution resolution,
      long version,
      Long referralBindingVersion,
      String pointBeneficiaryUserId,
      String cashPromoterUserId,
      CashTier cashTier,
      BigDecimal cashRate6,
      BigDecimal pointRate6) {
    this.orderId = orderId;
    this.orderCreatedAt = orderCreatedAt;
    this.benefitLookupUserId = benefitLookupUserId;
    this.resolution = resolution;
    this.version = version;
    this.referralBindingVersion = referralBindingVersion;
    this.pointBeneficiaryUserId = pointBeneficiaryUserId;
    this.cashPromoterUserId = cashPromoterUserId;
    this.cashTier = cashTier;
    this.cashRate6 = cashRate6;
    this.pointRate6 = pointRate6;
  }

  public static ReferralBenefitSnapshot pending(
      String orderId, Instant orderCreatedAt, String benefitLookupUserId, long version) {
    return new ReferralBenefitSnapshot(
        requireId(orderId, "orderId"),
        Objects.requireNonNull(orderCreatedAt, "orderCreatedAt"),
        requireId(benefitLookupUserId, "benefitLookupUserId"),
        BenefitResolution.PENDING,
        requireVersion(version),
        null,
        null,
        null,
        CashTier.NONE,
        ZERO,
        ZERO);
  }

  public ReferralBenefitSnapshot resolve(
      long expectedVersion,
      long referralBindingVersion,
      String pointBeneficiaryUserId,
      String cashPromoterUserId,
      CashTier cashTier,
      BigDecimal cashRate6,
      BigDecimal pointRate6) {
    ResolvedFacts facts =
        validateResolvedFacts(
            referralBindingVersion,
            pointBeneficiaryUserId,
            cashPromoterUserId,
            cashTier,
            cashRate6,
            pointRate6);
    if (resolution != BenefitResolution.PENDING) {
      if (resolution == BenefitResolution.RESOLVED && matches(facts)) {
        requireReplayVersion(expectedVersion);
        return this;
      }
      throw new IllegalStateException("terminal benefit resolution cannot be overwritten");
    }
    requireExpectedVersion(expectedVersion);
    return new ReferralBenefitSnapshot(
        orderId,
        orderCreatedAt,
        benefitLookupUserId,
        BenefitResolution.RESOLVED,
        nextVersion(),
        facts.referralBindingVersion,
        facts.pointBeneficiaryUserId,
        facts.cashPromoterUserId,
        facts.cashTier,
        facts.cashRate6,
        facts.pointRate6);
  }

  public ReferralBenefitSnapshot resolveNone(long expectedVersion) {
    if (resolution == BenefitResolution.NONE) {
      requireReplayVersion(expectedVersion);
      return this;
    }
    if (resolution == BenefitResolution.RESOLVED) {
      throw new IllegalStateException("terminal benefit resolution cannot be overwritten");
    }
    requireExpectedVersion(expectedVersion);
    return new ReferralBenefitSnapshot(
        orderId,
        orderCreatedAt,
        benefitLookupUserId,
        BenefitResolution.NONE,
        nextVersion(),
        null,
        null,
        null,
        CashTier.NONE,
        ZERO,
        ZERO);
  }

  public String orderId() {
    return orderId;
  }

  public Instant orderCreatedAt() {
    return orderCreatedAt;
  }

  public String benefitLookupUserId() {
    return benefitLookupUserId;
  }

  public BenefitResolution resolution() {
    return resolution;
  }

  public long version() {
    return version;
  }

  public OptionalLong referralBindingVersion() {
    return referralBindingVersion == null
        ? OptionalLong.empty()
        : OptionalLong.of(referralBindingVersion);
  }

  public Optional<String> pointBeneficiaryUserId() {
    return Optional.ofNullable(pointBeneficiaryUserId);
  }

  public Optional<String> cashPromoterUserId() {
    return Optional.ofNullable(cashPromoterUserId);
  }

  public CashTier cashTier() {
    return cashTier;
  }

  public BigDecimal cashRate6() {
    return cashRate6;
  }

  public BigDecimal pointRate6() {
    return pointRate6;
  }

  @Override
  public String toString() {
    return "ReferralBenefitSnapshot[resolution=" + resolution + ", version=" + version + ']';
  }

  private boolean matches(ResolvedFacts facts) {
    return referralBindingVersion.equals(facts.referralBindingVersion)
        && Objects.equals(pointBeneficiaryUserId, facts.pointBeneficiaryUserId)
        && Objects.equals(cashPromoterUserId, facts.cashPromoterUserId)
        && cashTier == facts.cashTier
        && cashRate6.compareTo(facts.cashRate6) == 0
        && pointRate6.compareTo(facts.pointRate6) == 0;
  }

  private void requireExpectedVersion(long expectedVersion) {
    if (expectedVersion != version) {
      throw new IllegalStateException("expectedVersion does not match benefit snapshot version");
    }
  }

  private void requireReplayVersion(long expectedVersion) {
    if (expectedVersion != version - 1) {
      throw new IllegalStateException("expectedVersion does not match original benefit command");
    }
  }

  private long nextVersion() {
    if (version == Long.MAX_VALUE) {
      throw new IllegalStateException("benefit snapshot version cannot advance");
    }
    return version + 1;
  }

  private static ResolvedFacts validateResolvedFacts(
      long referralBindingVersion,
      String pointBeneficiaryUserId,
      String cashPromoterUserId,
      CashTier cashTier,
      BigDecimal cashRate6,
      BigDecimal pointRate6) {
    if (referralBindingVersion <= 0) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    String validatedPointBeneficiary = optionalId(pointBeneficiaryUserId, "pointBeneficiaryUserId");
    String validatedCashPromoter = optionalId(cashPromoterUserId, "cashPromoterUserId");
    CashTier validatedTier = Objects.requireNonNull(cashTier, "cashTier");
    BigDecimal validatedCashRate = requireRate(cashRate6, "cashRate6");
    BigDecimal validatedPointRate = requireRate(pointRate6, "pointRate6");

    if ((validatedPointBeneficiary == null) != (validatedPointRate.signum() == 0)) {
      throw new IllegalArgumentException("point beneficiary and point rate must be present together");
    }
    if ((validatedCashPromoter == null) != (validatedCashRate.signum() == 0)
        || (validatedTier == CashTier.NONE) != (validatedCashRate.signum() == 0)) {
      throw new IllegalArgumentException("cash promoter, tier, and rate must be present together");
    }
    if (validatedCashRate.signum() == 0 && validatedPointRate.signum() == 0) {
      throw new IllegalArgumentException("resolved snapshot must contain at least one benefit");
    }
    return new ResolvedFacts(
        referralBindingVersion,
        validatedPointBeneficiary,
        validatedCashPromoter,
        validatedTier,
        validatedCashRate,
        validatedPointRate);
  }

  private static BigDecimal requireRate(BigDecimal value, String name) {
    BigDecimal rate = Objects.requireNonNull(value, name);
    if (rate.scale() > 6) {
      throw new IllegalArgumentException(name + " must have at most six decimal places");
    }
    if (rate.compareTo(ZERO) < 0 || rate.compareTo(ONE) > 0) {
      throw new IllegalArgumentException(name + " must be between zero and one");
    }
    return rate.signum() == 0 ? ZERO : rate.stripTrailingZeros();
  }

  private static long requireVersion(long version) {
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    return version;
  }

  private static String optionalId(String value, String name) {
    return value == null ? null : requireId(value, name);
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(name + " is too long");
    }
    return value;
  }

  private record ResolvedFacts(
      long referralBindingVersion,
      String pointBeneficiaryUserId,
      String cashPromoterUserId,
      CashTier cashTier,
      BigDecimal cashRate6,
      BigDecimal pointRate6) {}
}
