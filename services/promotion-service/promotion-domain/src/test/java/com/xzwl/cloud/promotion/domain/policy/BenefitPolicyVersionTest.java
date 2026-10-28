package com.xzwl.cloud.promotion.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenefitPolicyVersionTest {

  private static final String PROMOTER_ID = "promoter-sensitive-42";

  @Test
  void resolvesGlobalPointsAndIndependentCashTierOverrides() {
    PromoterCashRateOverride override =
        new PromoterCashRateOverride(PROMOTER_ID, Rate6.ZERO, Rate6.ONE);
    BenefitPolicyVersion policy = policy(Map.of(PROMOTER_ID, override));

    assertThat(policy.pointRate()).isEqualTo(Rate6.parse("0.020000"));
    assertThat(policy.cashRate(PROMOTER_ID, CashTier.LEVEL_1)).isEqualTo(Rate6.ZERO);
    assertThat(policy.cashRate(PROMOTER_ID, CashTier.LEVEL_2)).isEqualTo(Rate6.ONE);
    assertThat(policy.cashRate(null, CashTier.NONE)).isEqualTo(Rate6.ZERO);
    assertThatThrownBy(() -> policy.cashRate(PROMOTER_ID, CashTier.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
    assertThat(policy.cashRate("another-promoter", CashTier.LEVEL_1))
        .isEqualTo(Rate6.parse("0.030000"));
    assertThat(policy.cashRate("another-promoter", CashTier.LEVEL_2))
        .isEqualTo(Rate6.parse("0.010000"));
  }

  @Test
  void defensivelyCopiesAndDoesNotExposeTheOverrideMapForMutation() {
    Map<String, PromoterCashRateOverride> source = new HashMap<>();
    source.put(
        PROMOTER_ID,
        new PromoterCashRateOverride(
            PROMOTER_ID, Rate6.parse("0.040000"), Rate6.parse("0.050000")));
    BenefitPolicyVersion policy = policy(source);

    source.clear();

    assertThat(policy.cashRate(PROMOTER_ID, CashTier.LEVEL_1))
        .isEqualTo(Rate6.parse("0.040000"));
    assertThatThrownBy(() -> policy.cashRateOverrides().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsInvalidLookupAndConstructionBoundariesWithoutLeakingPromoterIds() {
    BenefitPolicyVersion policy = policy(Map.of());

    assertThatThrownBy(() -> policy.cashRate(" ", CashTier.LEVEL_1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
    assertThatThrownBy(() -> policy.cashRate(" ", CashTier.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
    assertThatThrownBy(() -> policy.cashRate(PROMOTER_ID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
    assertThatThrownBy(
            () ->
                new PromoterCashRateOverride(
                    " ", Rate6.parse("0.1"), Rate6.parse("0.2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
    assertThatThrownBy(
            () ->
                policy(
                    Map.of(
                        "different-map-key",
                        new PromoterCashRateOverride(
                            PROMOTER_ID, Rate6.parse("0.1"), Rate6.parse("0.2")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PROMOTER_ID);
  }

  @Test
  void diagnosticStringsDoNotRevealPromoterIdentity() {
    PromoterCashRateOverride override =
        new PromoterCashRateOverride(
            PROMOTER_ID, Rate6.parse("0.1"), Rate6.parse("0.2"));
    BenefitPolicyVersion policy = policy(Map.of(PROMOTER_ID, override));

    assertThat(override.toString()).doesNotContain(PROMOTER_ID);
    assertThat(policy.toString()).doesNotContain(PROMOTER_ID);
  }

  @Test
  void versionNumberStartsAtOneAndRequiredFieldsCannotBeNull() {
    assertThatThrownBy(
            () ->
                new BenefitPolicyVersion(
                    "benefit-policy",
                    0,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Rate6.ZERO,
                    Rate6.ZERO,
                    Rate6.ZERO,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new BenefitPolicyVersion(
                    "benefit-policy", 1, null, Rate6.ZERO, Rate6.ZERO, Rate6.ZERO, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static BenefitPolicyVersion policy(Map<String, PromoterCashRateOverride> overrides) {
    return new BenefitPolicyVersion(
        "benefit-policy",
        1,
        Instant.parse("2026-01-01T00:00:00Z"),
        Rate6.parse("0.020000"),
        Rate6.parse("0.030000"),
        Rate6.parse("0.010000"),
        overrides);
  }
}
