package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PaymentOrderStatus;
import java.util.Objects;

/** Applies both callback and polling confirmations through one idempotent state transition. */
public final class ConfirmPaymentHandler {

  private final PaymentOrderStore store;

  public ConfirmPaymentHandler(PaymentOrderStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public PaymentOrder handle(ConfirmPaymentCommand command) {
    ConfirmPaymentCommand requested = Objects.requireNonNull(command, "command");
    PaymentOrder current = store.findById(requested.orderId())
        .orElseThrow(() -> new IllegalArgumentException("payment order not found: " + requested.orderId()));
    PaymentOrder updated = current.confirmAt(requested.confirmationId(), requested.confirmedAt());
    if (updated == current) return current;
    if (updated.status() == PaymentOrderStatus.PAID) {
      store.loadFirstRechargeTypeLock(updated.userId()).lock(updated.rechargeType());
    }
    store.save(updated);
    return updated;
  }
}
