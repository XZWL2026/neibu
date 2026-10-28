package com.xzwl.cloud.billing.application.recharge;

import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.Objects;

/** Cash recharge facts retain the order's rate snapshot, independently of administrator credits. */
public record RechargeCreditRecord(
    String orderId, String userId, String walletId, String keyId,
    Decimal4 amount4, Decimal4 rateSnapshot4, Instant paidAt) {

  public RechargeCreditRecord {
    requireId(orderId, "orderId");
    requireId(userId, "userId");
    requireId(walletId, "walletId");
    requireId(keyId, "keyId");
    Objects.requireNonNull(amount4, "amount4");
    Objects.requireNonNull(rateSnapshot4, "rateSnapshot4");
    Objects.requireNonNull(paidAt, "paidAt");
    if (!amount4.isPositive() || !rateSnapshot4.isPositive()
        || !amount4.multiply(rateSnapshot4).isPositive()) {
      throw new IllegalArgumentException("recharge amount, rate and converted points must be positive");
    }
  }

  public Decimal4 points4() {
    return amount4.multiply(rateSnapshot4);
  }

  public FundingLot fundingLot() {
    return new FundingLot("recharge:" + orderId, walletId, FundingLotType.REAL_RECHARGE,
        points4(), points4(), paidAt, true);
  }

  private static void requireId(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
  }
}
