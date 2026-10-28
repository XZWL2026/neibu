package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PaymentOrderStatus;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class RefundLatePaymentHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    refundsOnlyARefundPendingOrderAndPersistsTheTerminalState();
    repeatedRefundIsIdempotentWithoutCallingTheProvider();
    nonRefundPendingOrderIsRejectedBeforeProviderCall();
    providerFailureLeavesTheOrderUnchanged();
    System.out.println("RefundLatePaymentHandlerTest passed: 4");
  }

  private static void refundsOnlyARefundPendingOrderAndPersistsTheTerminalState() {
    RecordingStore store = new RecordingStore();
    RecordingProvider provider = new RecordingProvider();
    PaymentOrder pending = refundPending();
    store.orders.put("order-1", pending);

    PaymentOrder result = new RefundLatePaymentHandler(store, provider).handle(command());

    check(result.status() == PaymentOrderStatus.REFUNDED, "refund reaches terminal state");
    check(result.id().equals("order-1"), "refunded order id");
    check(provider.calls == 1, "provider is called once");
    check(provider.order == pending, "provider receives the pending order");
    check(provider.refundId.equals("refund-1"), "provider receives the refund id");
    check(store.saveCalls == 1, "terminal refund is saved once");
  }

  private static void repeatedRefundIsIdempotentWithoutCallingTheProvider() {
    RecordingStore store = new RecordingStore();
    RecordingProvider provider = new RecordingProvider();
    PaymentOrder refunded = refundPending().markRefunded("refund-1", T0.plusSeconds(902));
    store.orders.put("order-1", refunded);

    PaymentOrder result = new RefundLatePaymentHandler(store, provider).handle(command());

    check(result == refunded, "same refund returns the terminal order");
    check(provider.calls == 0, "terminal replay does not call the provider");
    check(store.saveCalls == 0, "terminal replay does not save");
  }

  private static void nonRefundPendingOrderIsRejectedBeforeProviderCall() {
    RecordingStore store = new RecordingStore();
    RecordingProvider provider = new RecordingProvider();
    store.orders.put("order-1", PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.PERSONAL, T0));

    expectThrows(IllegalStateException.class,
        () -> new RefundLatePaymentHandler(store, provider).handle(command()));

    check(provider.calls == 0, "non-pending order does not call the provider");
    check(store.saveCalls == 0, "non-pending order is not saved");
  }

  private static void providerFailureLeavesTheOrderUnchanged() {
    RecordingStore store = new RecordingStore();
    RecordingProvider provider = new RecordingProvider();
    provider.failure = new IllegalStateException("provider unavailable");
    PaymentOrder pending = refundPending();
    store.orders.put("order-1", pending);

    IllegalStateException failure = expectThrows(IllegalStateException.class,
        () -> new RefundLatePaymentHandler(store, provider).handle(command()));

    check(failure == provider.failure, "provider exception is propagated");
    check(store.orders.get("order-1") == pending, "provider failure leaves the order unchanged");
    check(store.saveCalls == 0, "provider failure is not saved");
  }

  private static RefundLatePaymentCommand command() {
    return new RefundLatePaymentCommand("order-1", "refund-1", T0.plusSeconds(902));
  }

  private static PaymentOrder refundPending() {
    return PaymentOrder.pending(
            "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.PERSONAL, T0)
        .confirmAt("provider-confirm-1", T0.plusSeconds(901));
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

  private static final class RecordingStore implements PaymentRefundStore {
    private final Map<String, PaymentOrder> orders = new HashMap<>();
    private int saveCalls;

    @Override
    public Optional<PaymentOrder> findById(String orderId) {
      return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public void save(PaymentOrder order) {
      orders.put(order.id(), order);
      saveCalls++;
    }
  }

  private static final class RecordingProvider implements PaymentRefundPort {
    private int calls;
    private PaymentOrder order;
    private String refundId;
    private IllegalStateException failure;

    @Override
    public void refund(PaymentOrder order, String refundId) {
      calls++;
      this.order = order;
      this.refundId = refundId;
      if (failure != null) throw failure;
    }
  }
}
