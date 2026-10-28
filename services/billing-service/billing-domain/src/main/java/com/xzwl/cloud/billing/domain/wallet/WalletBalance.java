package com.xzwl.cloud.billing.domain.wallet;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

/** Immutable available-points balance used by billing application use cases. */
public record WalletBalance(String walletId, Decimal4 availablePoints4, long version) {

  public WalletBalance {
    if (walletId == null || walletId.isBlank()) {
      throw new IllegalArgumentException("walletId must not be blank");
    }
    availablePoints4 = Objects.requireNonNull(availablePoints4, "availablePoints4");
    if (availablePoints4.isNegative() || version < 1) {
      throw new IllegalArgumentException("balance and version must be non-negative and positive");
    }
  }

  public static WalletBalance zero(String walletId) {
    return new WalletBalance(walletId, Decimal4.zero(), 1);
  }

  public WalletBalance credit(Decimal4 points4) {
    Decimal4 points = Objects.requireNonNull(points4, "points4");
    if (!points.isPositive()) {
      throw new IllegalArgumentException("points4 must be positive");
    }
    return new WalletBalance(walletId, availablePoints4.add(points), version + 1);
  }
}
