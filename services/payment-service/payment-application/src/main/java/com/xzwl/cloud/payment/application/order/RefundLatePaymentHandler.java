package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PaymentOrderStatus;
import java.util.Objects;

/** Calls the refund provider only for a late payment awaiting refund. */
public final class RefundLatePaymentHandler {

  private final PaymentRefundStore store;
  private final PaymentRefundPort provider;

  public RefundLatePaymentHandler(PaymentRefundStore store, PaymentRefundPort provider) {
    this.store = Objects.requireNonNull(store, "store");
    this.provider = Objects.requireNonNull(provider, "provider");
  }

  public PaymentOrder handle(RefundLatePaymentCommand command) {
    RefundLatePaymentCommand requested = Objects.requireNonNull(command, "command");
    PaymentOrder current =
        store.findById(requested.orderId())
            .orElseThrow(() -> new IllegalArgumentException("payment order not found: " + requested.orderId()));
    if (current.status() == PaymentOrderStatus.REFUNDED) return current;
    if (current.status() != PaymentOrderStatus.REFUND_PENDING) {
      throw new IllegalStateException("only a refund-pending order can be refunded");
    }

    PaymentOrder refunded = current.markRefunded(requested.refundId(), requested.refundedAt());
    provider.refund(current, requested.refundId());
    store.save(refunded);
    return refunded;
  }
}
