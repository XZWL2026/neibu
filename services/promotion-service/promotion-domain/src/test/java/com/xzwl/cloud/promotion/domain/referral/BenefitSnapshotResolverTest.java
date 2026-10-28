package com.xzwl.cloud.promotion.domain.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenefitSnapshotResolverTest {

  @Test
  void factoriesOwnTheirFixedCashTiersWithoutCallerInput() {
    assertThat(BenefitSnapshot.cashOnly("promoter-a").cashTier())
        .isEqualTo(CashTier.LEVEL_1);
    assertThat(BenefitSnapshot.pointsAndCash("inviter-b", "promoter-a").cashTier())
        .isEqualTo(CashTier.LEVEL_2);
    assertThat(BenefitSnapshot.pointsOnly("inviter-c").cashTier())
        .isEqualTo(CashTier.NONE);
  }

  @Test
  void cashTierContainsOnlyTheFixedThreeValues() {
    assertThat(CashTier.values())
        .containsExactly(CashTier.NONE, CashTier.LEVEL_1, CashTier.LEVEL_2);
  }

  @Test
  void constrainedFactoriesCreateOnlyValidBenefitCombinations() {
    BenefitSnapshot none = BenefitSnapshot.none();
    BenefitSnapshot cashOnly = BenefitSnapshot.cashOnly("promoter-a");
    BenefitSnapshot pointsAndCash = BenefitSnapshot.pointsAndCash("inviter-b", "promoter-a");
    BenefitSnapshot pointsOnly = BenefitSnapshot.pointsOnly("inviter-c");

    assertThat(none.pointBeneficiaryUserId()).isNull();
    assertThat(none.cashPromoterUserId()).isNull();
    assertThat(none.cashTier()).isEqualTo(CashTier.NONE);
    assertThat(cashOnly.pointBeneficiaryUserId()).isNull();
    assertThat(cashOnly.cashPromoterUserId()).isEqualTo("promoter-a");
    assertThat(cashOnly.cashTier()).isEqualTo(CashTier.LEVEL_1);
    assertThat(pointsAndCash.pointBeneficiaryUserId()).isEqualTo("inviter-b");
    assertThat(pointsAndCash.cashPromoterUserId()).isEqualTo("promoter-a");
    assertThat(pointsAndCash.cashTier()).isEqualTo(CashTier.LEVEL_2);
    assertThat(pointsOnly.pointBeneficiaryUserId()).isEqualTo("inviter-c");
    assertThat(pointsOnly.cashPromoterUserId()).isNull();
    assertThat(pointsOnly.cashTier()).isEqualTo(CashTier.NONE);
  }

  @Test
  void noneSnapshotHasStableValueSemanticsAndNoIdentityToLeak() {
    BenefitSnapshot none = BenefitSnapshot.none();

    assertThat(none)
        .isEqualTo(BenefitSnapshot.none())
        .hasSameHashCodeAs(BenefitSnapshot.none());
    assertThat(none.toString())
        .contains("cashTier=NONE", "pointBeneficiaryPresent=false", "cashPromoterPresent=false")
        .doesNotContain("user-", "promoter-");
  }

  @Test
  void factoriesRejectNullBlankAndContradictoryInputs() {
    assertThatThrownBy(() -> BenefitSnapshot.cashOnly(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenefitSnapshot.cashOnly(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenefitSnapshot.pointsAndCash(
            " ", "promoter-a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenefitSnapshot.pointsAndCash(
            "inviter-b", " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenefitSnapshot.pointsOnly(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotTextNeverLeaksBeneficiaryOrPromoterIdentifiers() {
    String pointId = "secret-point-user-8472";
    String cashId = "secret-cash-user-9381";

    String text = BenefitSnapshot.pointsAndCash(pointId, cashId).toString();

    assertThat(text)
        .contains("LEVEL_2")
        .doesNotContain(pointId)
        .doesNotContain(cashId);
  }

  @Test
  void onlyDirectAgentInvitationsProduceCashForAThroughD() {
    BenefitSnapshot b = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-a", true, null));
    BenefitSnapshot c = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-b", false, b));
    BenefitSnapshot d = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-c", false, c));

    assertThat(b).isEqualTo(BenefitSnapshot.cashOnly("user-a"));
    assertThat(c).isEqualTo(BenefitSnapshot.pointsOnly("user-b"));
    assertThat(d).isEqualTo(BenefitSnapshot.pointsOnly("user-c"));
    assertThat(d.cashPromoterUserId()).isNull();
    assertThat(d.toString()).doesNotContain("user-a", "user-b", "user-c");
  }

  @Test
  void noneOrLevelTwoExistingSnapshotNeverRollsCashForward() {
    BenefitSnapshot fromNone = BenefitSnapshotResolver.resolve(new DirectInviterFacts(
        "user-d", false, BenefitSnapshot.none()));
    BenefitSnapshot fromLevelTwo = BenefitSnapshotResolver.resolve(new DirectInviterFacts(
        "user-c", false,
        BenefitSnapshot.pointsAndCash("user-b", "user-a")));

    assertThat(fromNone).isEqualTo(BenefitSnapshot.pointsOnly("user-d"));
    assertThat(fromLevelTwo).isEqualTo(BenefitSnapshot.pointsOnly("user-c"));
  }

  @Test
  void laterPromoterApprovalAffectsOnlyNewInvitationsAndDoesNotRewriteHistory() {
    BenefitSnapshot historicalB = BenefitSnapshot.cashOnly("user-a");
    BenefitSnapshot historicalC = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-b", false, historicalB));

    BenefitSnapshot e = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-b", true, historicalB));
    BenefitSnapshot f = BenefitSnapshotResolver.resolve(
        new DirectInviterFacts("user-e", false, e));

    assertThat(historicalC).isEqualTo(BenefitSnapshot.pointsOnly("user-b"));
    assertThat(e).isEqualTo(BenefitSnapshot.cashOnly("user-b"));
    assertThat(f).isEqualTo(BenefitSnapshot.pointsOnly("user-e"));
    assertThat(e.toString()).doesNotContain("user-a");
    assertThat(f.toString()).doesNotContain("user-a");
  }

  @Test
  void directInviterFactsValidateTheirOnlyIdentifierAndSnapshot() {
    assertThatThrownBy(() -> new DirectInviterFacts(null, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DirectInviterFacts(" ", false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new DirectInviterFacts("user-a", true, null).existingBenefitSnapshot()).isNull();
  }

  @Test
  void resolverRejectsNullFacts() {
    assertThatThrownBy(() -> BenefitSnapshotResolver.resolve(null))
        .isInstanceOf(NullPointerException.class);
  }
}
