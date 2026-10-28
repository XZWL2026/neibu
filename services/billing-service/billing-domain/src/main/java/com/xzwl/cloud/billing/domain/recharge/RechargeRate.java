package com.xzwl.cloud.billing.domain.recharge;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

/** Current conversion configuration; a payment order retains its own immutable snapshot. */
public record RechargeRate(Decimal4 pointsPerYuan4, long version) {

  public RechargeRate {
    Objects.requireNonNull(pointsPerYuan4, "pointsPerYuan4");
    if (!pointsPerYuan4.isPositive() || pointsPerYuan4.asBigDecimal().precision() > 38) {
      throw new IllegalArgumentException("pointsPerYuan4 must be positive and fit DECIMAL(38,4)");
    }
    if (version < 1) throw new IllegalArgumentException("version must be positive");
  }
}
