package com.xzwl.cloud.payment.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Finds a still-valid pending order by the product-defined reuse key. */
public final class PendingPaymentReuse {

  private PendingPaymentReuse() {}

  public static Optional<PaymentOrder> find(
      String userId,
      BigDecimal amount4,
      RechargeType rechargeType,
      Instant now,
      List<PaymentOrder> orders) {
    String user = requireId(userId, "userId");
    BigDecimal amount = Objects.requireNonNull(amount4, "amount4").setScale(4, RoundingMode.DOWN);
    if (amount.signum() <= 0) throw new IllegalArgumentException("amount4 must be positive");
    RechargeType type = Objects.requireNonNull(rechargeType, "rechargeType");
    Instant current = Objects.requireNonNull(now, "now");
    return Objects.requireNonNull(orders, "orders").stream()
        .filter(order -> order != null)
        .filter(order -> user.equals(order.userId()))
        .filter(order -> order.amount4().compareTo(amount) == 0)
        .filter(order -> order.rechargeType() == type)
        .filter(order -> order.isPendingAt(current))
        .findFirst();
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }
}
