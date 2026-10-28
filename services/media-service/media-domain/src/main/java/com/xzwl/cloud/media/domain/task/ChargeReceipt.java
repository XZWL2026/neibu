package com.xzwl.cloud.media.domain.task;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Billing's persisted receipt, including the original four-decimal points and rate. */
public record ChargeReceipt(String chargeId, BigDecimal amount, BigDecimal rate, BigDecimal points) {
  public ChargeReceipt {
    MediaReference.identifier(chargeId, "chargeId");
    amount = decimal(amount);
    rate = decimal(rate);
    points = decimal(points);
    if (rate.signum() == 0) throw new IllegalArgumentException("rate must be positive");
  }

  private static BigDecimal decimal(BigDecimal value) {
    BigDecimal result = Objects.requireNonNull(value).setScale(4, RoundingMode.UNNECESSARY);
    if (result.signum() < 0 || result.precision() > 20) throw new IllegalArgumentException("invalid money");
    return result;
  }

  public BigDecimal refundPoints(int failed, int total) {
    if (total < 1 || failed < 0 || failed > total) throw new IllegalArgumentException("invalid refund count");
    return failed == total ? points
        : points.multiply(BigDecimal.valueOf(failed)).divide(BigDecimal.valueOf(total), 4, RoundingMode.DOWN);
  }
}
