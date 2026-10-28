package com.xzwl.cloud.payment.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PendingPaymentReuseTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    reusesOnlyAnActiveOrderWithTheSameUserAmountAndType();
    System.out.println("PendingPaymentReuseTest passed: 1");
  }

  private static void reusesOnlyAnActiveOrderWithTheSameUserAmountAndType() {
    Instant createdAt = Instant.parse("2026-09-05T00:00:00Z");
    PaymentOrder match = PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("100"), BigDecimal.ONE, RechargeType.PERSONAL, createdAt);
    PaymentOrder expired = PaymentOrder.pending(
        "order-2", "user-1", new BigDecimal("100"), BigDecimal.ONE, RechargeType.PERSONAL,
        createdAt.minusSeconds(901));
    PaymentOrder differentAmount = PaymentOrder.pending(
        "order-3", "user-1", new BigDecimal("50"), BigDecimal.ONE, RechargeType.PERSONAL, createdAt);

    check(PendingPaymentReuse.find(
            "user-1", new BigDecimal("100.00"), RechargeType.PERSONAL,
            createdAt.plusSeconds(60), List.of(expired, differentAmount, match))
        .orElseThrow().equals(match), "active same-order semantics are reused");
    check(PendingPaymentReuse.find(
            "user-1", new BigDecimal("100"), RechargeType.API,
            createdAt.plusSeconds(60), List.of(match)).isEmpty(),
        "different recharge type is not reused");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
