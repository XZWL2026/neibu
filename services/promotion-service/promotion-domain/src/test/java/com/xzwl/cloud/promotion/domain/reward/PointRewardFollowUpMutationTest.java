package com.xzwl.cloud.promotion.domain.reward;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PointRewardFollowUpMutationTest {

  private static final Instant NOW = Instant.parse("2026-08-31T01:00:00Z");

  @ParameterizedTest(name = "grant offset rejects {0}")
  @EnumSource(FollowUpMutation.class)
  void grantOffsetRejectsEveryMutatedFollowUpField(FollowUpMutation mutation) {
    PointRewardAccount account = accountWithDebt();
    PointRewardWriteResult valid = grantFuture(account);

    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    List.of(
                        valid.entries().getFirst(),
                        mutation.mutate(valid.entries().getLast())),
                    valid.snapshot(),
                    valid.netRewardToCredit()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "reverse debt rejects {0}")
  @EnumSource(FollowUpMutation.class)
  void reversalDebtRejectsEveryMutatedFollowUpField(FollowUpMutation mutation) {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    grantOriginal(account);
    PointRewardWriteResult valid = reverseOriginal(account);

    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    List.of(
                        valid.entries().getFirst(),
                        mutation.mutate(valid.entries().getLast())),
                    valid.snapshot(),
                    valid.netRewardToCredit()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PointRewardAccount accountWithDebt() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    grantOriginal(account);
    reverseOriginal(account);
    return account;
  }

  private static void grantOriginal(PointRewardAccount account) {
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
  }

  private static PointRewardWriteResult reverseOriginal(PointRewardAccount account) {
    return account.reverse(
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
  }

  private static PointRewardWriteResult grantFuture(PointRewardAccount account) {
    return account.grant(
        "future",
        "entitlement-2",
        "settlement-2",
        "allocation-2",
        "lot-2",
        "consumer-2",
        2,
        CashTier.LEVEL_1,
        amount("9"),
        NOW.plusSeconds(1));
  }

  private enum FollowUpMutation {
    SEQUENCE,
    CHAIN,
    BENEFICIARY,
    SOURCE_EVENT,
    ENTITLEMENT,
    SETTLEMENT,
    ALLOCATION,
    FUNDING_LOT,
    WALLET_OWNER,
    BINDING_VERSION,
    CASH_TIER,
    CREATED_AT,
    ACTION,
    AMOUNT,
    REASON,
    RECOVERED_NOW;

    ReferralPointEntitlement mutate(ReferralPointEntitlement original) {
      PointRewardActionType action = original.actionType();
      PointRewardReason reason = original.reason();
      Decimal4 points = original.points4();
      PointRewardSnapshot before = original.before();
      RewardSourceSnapshot source = original.sourceSnapshot();
      Decimal4 recovered = original.recoveredNow4();

      switch (this) {
        case CHAIN ->
            before =
                new PointRewardSnapshot(
                    before.cumulativeGranted().add(amount("1")),
                    before.cumulativeReversed(),
                    before.pointDebt());
        case ENTITLEMENT -> source = withEntitlement(source, "other-entitlement");
        case SETTLEMENT -> source = withSettlement(source, "other-settlement");
        case ALLOCATION -> source = withAllocation(source, "other-allocation");
        case FUNDING_LOT -> source = withLot(source, "other-lot");
        case WALLET_OWNER -> source = withOwner(source, "other-owner");
        case BINDING_VERSION -> source = withBinding(source, source.referralBindingVersion() + 1);
        case CASH_TIER ->
            source = withTier(source, source.cashTier() == CashTier.NONE ? CashTier.LEVEL_1 : CashTier.NONE);
        case ACTION -> {
          action = PointRewardActionType.POINT_REWARD_GRANTED;
          reason = PointRewardReason.TASK_SUCCESS_REWARD_GRANTED;
        }
        case AMOUNT -> points = amount("5");
        case REASON -> reason = PointRewardReason.TASK_SUCCESS_REWARD_GRANTED;
        case RECOVERED_NOW -> recovered = amount("1");
        default -> {
          // Remaining scalar mutations are applied at construction below.
        }
      }

      PointRewardSnapshot after = transition(before, action, points);
      return new ReferralPointEntitlement(
          sequenceNo(original),
          beneficiary(original),
          action,
          points,
          before,
          after,
          sourceEvent(original),
          source,
          recovered,
          createdAt(original),
          reason);
    }

    private long sequenceNo(ReferralPointEntitlement original) {
      return this == SEQUENCE ? original.sequenceNo() + 1 : original.sequenceNo();
    }

    private String beneficiary(ReferralPointEntitlement original) {
      return this == BENEFICIARY ? "other-beneficiary" : original.beneficiaryUserId();
    }

    private String sourceEvent(ReferralPointEntitlement original) {
      return this == SOURCE_EVENT ? "other-event" : original.sourceEventId();
    }

    private Instant createdAt(ReferralPointEntitlement original) {
      return this == CREATED_AT ? original.createdAt().plusSeconds(1) : original.createdAt();
    }
  }

  private static PointRewardSnapshot transition(
      PointRewardSnapshot before, PointRewardActionType action, Decimal4 points) {
    return switch (action) {
      case POINT_REWARD_GRANTED -> before.granted(points);
      case POINT_REWARD_REVERSED -> before.reversed(points);
      case POINT_DEBT_CREATED -> before.debtCreated(points);
      case POINT_DEBT_OFFSET -> before.debtOffset(points);
    };
  }

  private static RewardSourceSnapshot withEntitlement(RewardSourceSnapshot source, String value) {
    return new RewardSourceSnapshot(
        value,
        source.sourceSettlementId(),
        source.sourceAllocationId(),
        source.sourceFundingLotId(),
        source.sourceWalletOwnerUserId(),
        source.referralBindingVersion(),
        source.cashTier());
  }

  private static RewardSourceSnapshot withSettlement(RewardSourceSnapshot source, String value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        value,
        source.sourceAllocationId(),
        source.sourceFundingLotId(),
        source.sourceWalletOwnerUserId(),
        source.referralBindingVersion(),
        source.cashTier());
  }

  private static RewardSourceSnapshot withAllocation(RewardSourceSnapshot source, String value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        source.sourceSettlementId(),
        value,
        source.sourceFundingLotId(),
        source.sourceWalletOwnerUserId(),
        source.referralBindingVersion(),
        source.cashTier());
  }

  private static RewardSourceSnapshot withLot(RewardSourceSnapshot source, String value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        source.sourceSettlementId(),
        source.sourceAllocationId(),
        value,
        source.sourceWalletOwnerUserId(),
        source.referralBindingVersion(),
        source.cashTier());
  }

  private static RewardSourceSnapshot withOwner(RewardSourceSnapshot source, String value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        source.sourceSettlementId(),
        source.sourceAllocationId(),
        source.sourceFundingLotId(),
        value,
        source.referralBindingVersion(),
        source.cashTier());
  }

  private static RewardSourceSnapshot withBinding(RewardSourceSnapshot source, long value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        source.sourceSettlementId(),
        source.sourceAllocationId(),
        source.sourceFundingLotId(),
        source.sourceWalletOwnerUserId(),
        value,
        source.cashTier());
  }

  private static RewardSourceSnapshot withTier(RewardSourceSnapshot source, CashTier value) {
    return new RewardSourceSnapshot(
        source.rewardEntitlementId(),
        source.sourceSettlementId(),
        source.sourceAllocationId(),
        source.sourceFundingLotId(),
        source.sourceWalletOwnerUserId(),
        source.referralBindingVersion(),
        value);
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
