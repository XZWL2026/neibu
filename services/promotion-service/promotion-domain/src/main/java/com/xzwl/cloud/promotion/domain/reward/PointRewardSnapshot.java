package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;

/**
 * Promotion-side reward totals. This is not a Billing wallet balance and contains no spendable
 * points projection.
 */
public record PointRewardSnapshot(
    Decimal4 cumulativeGranted, Decimal4 cumulativeReversed, Decimal4 pointDebt) {

  public PointRewardSnapshot {
    PointRewardAmounts.requireNonNegative(cumulativeGranted, "cumulativeGranted");
    PointRewardAmounts.requireNonNegative(cumulativeReversed, "cumulativeReversed");
    PointRewardAmounts.requireNonNegative(pointDebt, "pointDebt");
  }

  public static PointRewardSnapshot zero() {
    return new PointRewardSnapshot(Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO);
  }

  PointRewardSnapshot granted(Decimal4 points) {
    return new PointRewardSnapshot(cumulativeGranted.add(points), cumulativeReversed, pointDebt);
  }

  PointRewardSnapshot reversed(Decimal4 points) {
    return new PointRewardSnapshot(cumulativeGranted, cumulativeReversed.add(points), pointDebt);
  }

  PointRewardSnapshot debtCreated(Decimal4 points) {
    return new PointRewardSnapshot(cumulativeGranted, cumulativeReversed, pointDebt.add(points));
  }

  PointRewardSnapshot debtOffset(Decimal4 points) {
    return new PointRewardSnapshot(cumulativeGranted, cumulativeReversed, pointDebt.subtract(points));
  }
}
