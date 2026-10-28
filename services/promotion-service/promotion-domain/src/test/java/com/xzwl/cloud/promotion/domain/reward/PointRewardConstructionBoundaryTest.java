package com.xzwl.cloud.promotion.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PointRewardConstructionBoundaryTest {

  private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

  @Test
  void billingInstructionHasNoPublicConstructorAndOnlyPackageFactoryBuildsIt() throws Exception {
    assertThat(PointRewardWriteResult.class.getConstructors()).isEmpty();
    assertThat(
            Modifier.isPublic(
                PointRewardWriteResult.class
                    .getDeclaredMethod(
                        "fromLedgerFacts", List.class, PointRewardSnapshot.class, Decimal4.class)
                    .getModifiers()))
        .isFalse();

    PointRewardAccount account = accountWithSixPointDebt();
    PointRewardWriteResult result =
        account.grant(
            "future",
            "entitlement-2",
            "settlement-2",
            "allocation-2",
            "lot-2",
            "consumer-2",
            2,
            CashTier.NONE,
            amount("9"),
            NOW);

    assertThat(result.entries()).hasSize(2);
    assertThat(result.netRewardToCredit()).isEqualTo(amount("3"));
  }

  @Test
  void sourceSnapshotCentralizesIdentityValidationAndValueEquality() {
    RewardSourceSnapshot first = source("entitlement", CashTier.LEVEL_2);
    RewardSourceSnapshot same = source("entitlement", CashTier.LEVEL_2);

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first.rewardEntitlementId()).isEqualTo("entitlement");
    assertThatThrownBy(
            () ->
                new RewardSourceSnapshot(
                    " ", "settlement", "allocation", "lot", "consumer", 1, CashTier.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rewardEntitlementId");
    assertThatThrownBy(
            () ->
                new RewardSourceSnapshot(
                    "entitlement",
                    "x".repeat(201),
                    "allocation",
                    "lot",
                    "consumer",
                    1,
                    CashTier.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too long")
        .hasMessageNotContaining("x".repeat(201));
    assertThatThrownBy(
            () ->
                new RewardSourceSnapshot(
                    "entitlement", "settlement", "allocation", "lot", "consumer", 0, CashTier.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referralBindingVersion");
    assertThatThrownBy(
            () ->
                new RewardSourceSnapshot(
                    "entitlement", "settlement", "allocation", "lot", "consumer", 1, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static PointRewardAccount accountWithSixPointDebt() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    account.grant(
        "grant",
        "entitlement",
        "settlement",
        "allocation",
        "lot",
        "consumer",
        1,
        CashTier.NONE,
        amount("10"),
        NOW);
    account.reverse(
        "reverse",
        "entitlement",
        "settlement",
        "allocation",
        "lot",
        "consumer",
        1,
        CashTier.NONE,
        amount("8"),
        amount("2"),
        NOW);
    return account;
  }

  private static RewardSourceSnapshot source(String entitlement, CashTier tier) {
    return new RewardSourceSnapshot(
        entitlement, "settlement", "allocation", "lot", "consumer", 1, tier);
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
