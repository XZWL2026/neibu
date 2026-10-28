package com.xzwl.cloud.promotion.domain.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import com.xzwl.cloud.promotion.domain.policy.PromoterCashRateOverride;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import com.xzwl.cloud.promotion.domain.referral.DirectInviterFacts;
import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PromotionIdentifiersBoundaryTest {

  private static final String ID_200 = "a".repeat(200);
  private static final String ID_201 = "z".repeat(201);

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptingBoundaries")
  void acceptsTwoHundredCharacterIdentifiers(String name, Probe probe) {
    assertThatCode(probe::run).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectingBoundaries")
  void consistentlyRejectsLongIdentifiersWithoutEchoingThem(String name, Probe probe) {
    assertThatThrownBy(probe::run)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long")
        .hasMessageNotContaining(ID_201);
  }

  private static Stream<Arguments> acceptingBoundaries() {
    return Stream.of(
        Arguments.of("policy override", (Probe) () -> override(ID_200)),
        Arguments.of("policy lookup", (Probe) () -> policy(Map.of()).cashRate(ID_200, CashTier.LEVEL_1)),
        Arguments.of("benefit point", (Probe) () -> BenefitSnapshot.pointsOnly(ID_200)),
        Arguments.of("benefit cash", (Probe) () -> BenefitSnapshot.cashOnly(ID_200)),
        Arguments.of("direct inviter", (Probe) () -> new DirectInviterFacts(ID_200, false, null)),
        Arguments.of("commission", (Probe) () -> CommissionAccount.create(ID_200)),
        Arguments.of("point reward", (Probe) () -> PointRewardAccount.create(ID_200)));
  }

  private static Stream<Arguments> rejectingBoundaries() {
    return Stream.of(
        Arguments.of("policy override", (Probe) () -> override(ID_201)),
        Arguments.of("policy lookup", (Probe) () -> policy(Map.of()).cashRate(ID_201, CashTier.LEVEL_1)),
        Arguments.of(
            "policy override key",
            (Probe) () -> policy(Map.of(ID_201, override("valid-promoter")))),
        Arguments.of("benefit point", (Probe) () -> BenefitSnapshot.pointsOnly(ID_201)),
        Arguments.of("benefit cash", (Probe) () -> BenefitSnapshot.cashOnly(ID_201)),
        Arguments.of("direct inviter", (Probe) () -> new DirectInviterFacts(ID_201, false, null)),
        Arguments.of("commission", (Probe) () -> CommissionAccount.create(ID_201)),
        Arguments.of("point reward", (Probe) () -> PointRewardAccount.create(ID_201)));
  }

  private static PromoterCashRateOverride override(String promoterUserId) {
    return new PromoterCashRateOverride(promoterUserId, Rate6.ZERO, Rate6.ZERO);
  }

  private static BenefitPolicyVersion policy(
      Map<String, PromoterCashRateOverride> overrides) {
    return new BenefitPolicyVersion(
        "history",
        1,
        Instant.parse("2026-08-31T00:00:00Z"),
        Rate6.ZERO,
        Rate6.ZERO,
        Rate6.ZERO,
        overrides);
  }

  @FunctionalInterface
  private interface Probe {
    void run();
  }
}
