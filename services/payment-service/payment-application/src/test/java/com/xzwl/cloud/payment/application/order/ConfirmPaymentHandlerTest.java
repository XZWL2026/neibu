package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.FirstRechargeTypeLock;
import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PaymentOrderStatus;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ConfirmPaymentHandlerTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    callbackAndPollingShareOneIdempotentConfirmation();
    successfulFirstPaymentLocksRechargeTypeBeforeSave();
    conflictingFirstRechargeTypeIsRejectedWithoutSaving();
    latePaymentEntersRefundPendingWithoutCredit();
    System.out.println("ConfirmPaymentHandlerTest passed: 4");
  }

  private static void callbackAndPollingShareOneIdempotentConfirmation() {
    RecordingStore store = new RecordingStore();
    Instant created = Instant.parse("2026-09-05T00:00:00Z");
    store.orders.put("order-1", PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.PERSONAL, created));
    ConfirmPaymentHandler handler = new ConfirmPaymentHandler(store);
    ConfirmPaymentCommand command = new ConfirmPaymentCommand(
        "order-1", "provider-confirm-1", created.plusSeconds(60));

    PaymentOrder callback = handler.handle(command);
    PaymentOrder polling = handler.handle(command);

    check(callback.status() == PaymentOrderStatus.PAID, "callback confirms payment");
    check(polling == callback, "polling reuses callback result");
    check(store.saveCalls == 1, "same confirmation is saved once");
    check(store.lockLoads == 1, "idempotent replay does not reload the first recharge lock");
  }

  private static void successfulFirstPaymentLocksRechargeTypeBeforeSave() {
    RecordingStore store = new RecordingStore();
    Instant created = Instant.parse("2026-09-05T00:00:00Z");
    store.orders.put("order-1", PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.PERSONAL, created));

    PaymentOrder result = new ConfirmPaymentHandler(store).handle(new ConfirmPaymentCommand(
        "order-1", "provider-confirm-1", created.plusSeconds(60)));

    check(result.status() == PaymentOrderStatus.PAID, "payment is confirmed");
    check(store.firstRechargeType.lockedType() == RechargeType.PERSONAL,
        "successful first payment locks its recharge type");
    check(store.lockedBeforeSave, "first recharge type is locked before order save");
  }

  private static void conflictingFirstRechargeTypeIsRejectedWithoutSaving() {
    RecordingStore store = new RecordingStore();
    Instant created = Instant.parse("2026-09-05T00:00:00Z");
    store.firstRechargeType.lock(RechargeType.ENTERPRISE);
    store.orders.put("order-1", PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.PERSONAL, created));

    expectThrows(IllegalStateException.class, () ->
        new ConfirmPaymentHandler(store).handle(new ConfirmPaymentCommand(
            "order-1", "provider-confirm-1", created.plusSeconds(60))));

    check(store.saveCalls == 0, "conflicting first recharge type is not saved");
    check(store.orders.get("order-1").status() == PaymentOrderStatus.PENDING,
        "conflict leaves the pending order unchanged");
  }

  private static void latePaymentEntersRefundPendingWithoutCredit() {
    RecordingStore store = new RecordingStore();
    Instant created = Instant.parse("2026-09-05T00:00:00Z");
    store.orders.put("order-1", PaymentOrder.pending(
        "order-1", "user-1", new BigDecimal("10"), BigDecimal.ONE, RechargeType.ENTERPRISE, created));

    PaymentOrder result = new ConfirmPaymentHandler(store).handle(new ConfirmPaymentCommand(
        "order-1", "provider-confirm-late", created.plusSeconds(15 * 60)));

    check(result.status() == PaymentOrderStatus.REFUND_PENDING, "late payment is refund pending");
    check(store.saveCalls == 1, "late payment state is persisted once");
    check(store.lockLoads == 0, "late payment does not lock the recharge type");
    check(store.firstRechargeType.lockedType() == null, "late payment leaves recharge type unset");
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

  private static final class RecordingStore implements PaymentOrderStore {
    private final Map<String, PaymentOrder> orders = new HashMap<>();
    private final FirstRechargeTypeLock firstRechargeType = FirstRechargeTypeLock.create("user-1");
    private int saveCalls;
    private int lockLoads;
    private boolean lockedBeforeSave;

    @Override
    public Optional<PaymentOrder> findById(String orderId) {
      return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public FirstRechargeTypeLock loadFirstRechargeTypeLock(String userId) {
      check("user-1".equals(userId), "lock is loaded for the order user");
      lockLoads++;
      return firstRechargeType;
    }

    @Override
    public void save(PaymentOrder order) {
      lockedBeforeSave = firstRechargeType.lockedType() == order.rechargeType();
      orders.put(order.id(), order);
      saveCalls++;
    }
  }
}
