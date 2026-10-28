package com.xzwl.cloud.payment.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentOrderTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    confirmsWithinTheWindowIdempotently();
    latePaymentEntersRefundPendingAndCanBeClosedIdempotently();
    retainsTheCreationRateThroughPaymentAndRefund();
    System.out.println("PaymentOrderTest passed: 3");
  }

  private static void confirmsWithinTheWindowIdempotently() {
    PaymentOrder order = PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("100.0000"), BigDecimal.ONE, RechargeType.PERSONAL, T0);
    check(order.isPendingAt(T0.plusSeconds(899)), "order is pending before expiry");
    check(!order.isPendingAt(T0.plusSeconds(900)), "order expires at fifteen minutes");
    PaymentOrder paid = order.confirmPaid("provider-confirmation-1", T0.plusSeconds(60));
    check(paid.status() == PaymentOrderStatus.PAID, "paid state");
    check(paid.confirmPaid("provider-confirmation-1", T0.plusSeconds(60)) == paid,
        "same callback or poll is idempotent");
    expectThrows(IllegalStateException.class,
        () -> paid.confirmPaid("provider-confirmation-2", T0.plusSeconds(61)));
  }

  private static void latePaymentEntersRefundPendingAndCanBeClosedIdempotently() {
    PaymentOrder order = PaymentOrder.pending(
        "order-2", "user-1", new BigDecimal("50.0000"), BigDecimal.ONE, RechargeType.API, T0);
    PaymentOrder refundPending = order.confirmAt("provider-confirmation-2", T0.plusSeconds(901));
    check(refundPending.status() == PaymentOrderStatus.REFUND_PENDING, "late payment needs refund");
    PaymentOrder refunded = refundPending.markRefunded("refund-1", T0.plusSeconds(902));
    check(refunded.status() == PaymentOrderStatus.REFUNDED, "refund is terminal");
    check(refunded.markRefunded("refund-1", T0.plusSeconds(902)) == refunded,
        "refund completion is idempotent");
  }

  private static void retainsTheCreationRateThroughPaymentAndRefund() {
    PaymentOrder order = PaymentOrder.pending("snapshot-1", "user-1", new BigDecimal("10"),
        new BigDecimal("123.45679"), RechargeType.PERSONAL, T0);
    check(order.rateSnapshot4().equals(new BigDecimal("123.4567")), "rate is truncated at creation");
    check(order.confirmPaid("paid-1", T0.plusSeconds(60)).rateSnapshot4().equals(order.rateSnapshot4()),
        "paid order retains its creation rate");
    check(order.confirmAt("late-1", T0.plusSeconds(901))
        .markRefunded("refund-1", T0.plusSeconds(902)).rateSnapshot4().equals(order.rateSnapshot4()),
        "refund transitions retain the same snapshot");
    expectThrows(IllegalArgumentException.class, () -> PaymentOrder.pending(
        "bad-rate", "user-1", BigDecimal.ONE, BigDecimal.ZERO, RechargeType.PERSONAL, T0));
    expectThrows(IllegalArgumentException.class, () -> PaymentOrder.pending(
        "zero-points", "user-1", new BigDecimal("0.01"), new BigDecimal("0.0001"),
        RechargeType.PERSONAL, T0));
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) return type.cast(thrown);
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }
}
