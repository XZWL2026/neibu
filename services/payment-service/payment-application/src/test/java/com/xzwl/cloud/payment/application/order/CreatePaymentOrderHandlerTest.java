package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PaymentOrderStatus;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CreatePaymentOrderHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    reusesOnlyTheMatchingActivePendingOrder();
    createsAndSavesANormalizedPendingOrderWhenNoMatchExists();
    ignoresExpiredAndDifferentPendingOrders();
    System.out.println("CreatePaymentOrderHandlerTest passed: 3");
  }

  private static void reusesOnlyTheMatchingActivePendingOrder() {
    RecordingStore store = new RecordingStore();
    PaymentOrder match = pending("order-match", "100", RechargeType.PERSONAL, T0);
    store.orders.add(pending("order-expired", "100", RechargeType.PERSONAL, T0.minusSeconds(901)));
    store.orders.add(match);

    PaymentOrder result = new CreatePaymentOrderHandler(store).handle(command("new-order", "100.00"));

    check(result == match, "active matching order is reused");
    check(result.rateSnapshot4().compareTo(BigDecimal.TEN) == 0,
        "reusing a pending order keeps its original rate even when the configured rate changes");
    check(store.saveCalls == 0, "reused order is not saved");
    check(store.findCalls == 1, "pending orders are loaded once");
  }

  private static void createsAndSavesANormalizedPendingOrderWhenNoMatchExists() {
    RecordingStore store = new RecordingStore();

    PaymentOrder result = new CreatePaymentOrderHandler(store).handle(command("order-new", "10.12349"));

    check(result.status() == PaymentOrderStatus.PENDING, "new order is pending");
    check(result.id().equals("order-new"), "new order id");
    check(result.amount4().compareTo(new BigDecimal("10.1234")) == 0, "amount is truncated to four decimals");
    check(result.rateSnapshot4().equals(new BigDecimal("100.1234")),
        "new order saves the normalized creation rate");
    check(result.rechargeType() == RechargeType.PERSONAL, "recharge type");
    check(result.createdAt().equals(T0), "created at");
    check(store.saved == result, "new order is saved");
    check(store.saveCalls == 1, "new order is saved once");
  }

  private static void ignoresExpiredAndDifferentPendingOrders() {
    RecordingStore store = new RecordingStore();
    store.orders.add(pending("order-expired", "100", RechargeType.PERSONAL, T0.minusSeconds(901)));
    store.orders.add(pending("order-type", "100", RechargeType.ENTERPRISE, T0));
    store.orders.add(pending("order-amount", "50", RechargeType.PERSONAL, T0));

    PaymentOrder result = new CreatePaymentOrderHandler(store).handle(command("order-new", "100"));

    check(result.id().equals("order-new"), "nonmatching orders do not get reused");
    check(store.saveCalls == 1, "new order is saved after no match");
  }

  private static CreatePaymentOrderCommand command(String orderId, String amount) {
    return new CreatePaymentOrderCommand(
        orderId, "user-1", new BigDecimal(amount), new BigDecimal("100.12349"), RechargeType.PERSONAL, T0);
  }

  private static PaymentOrder pending(
      String orderId, String amount, RechargeType type, Instant createdAt) {
    return PaymentOrder.pending(orderId, "user-1", new BigDecimal(amount), BigDecimal.TEN, type, createdAt);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingStore implements PaymentOrderCreationStore {
    private final List<PaymentOrder> orders = new ArrayList<>();
    private int findCalls;
    private int saveCalls;
    private PaymentOrder saved;

    @Override
    public List<PaymentOrder> findPendingByUser(String userId) {
      check("user-1".equals(userId), "pending orders are loaded for the requested user");
      findCalls++;
      return List.copyOf(orders);
    }

    @Override
    public void save(PaymentOrder order) {
      saved = order;
      saveCalls++;
    }
  }
}
