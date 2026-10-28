package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import com.xzwl.cloud.payment.domain.order.PendingPaymentReuse;
import java.util.Objects;

/** Reuses one active matching pending order before creating a new order. */
public final class CreatePaymentOrderHandler {

  private final PaymentOrderCreationStore store;

  public CreatePaymentOrderHandler(PaymentOrderCreationStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public PaymentOrder handle(CreatePaymentOrderCommand command) {
    CreatePaymentOrderCommand requested = Objects.requireNonNull(command, "command");
    PaymentOrder existing =
        PendingPaymentReuse.find(
                requested.userId(),
                requested.amount4(),
                requested.rechargeType(),
                requested.createdAt(),
                store.findPendingByUser(requested.userId()))
            .orElse(null);
    if (existing != null) return existing;

    PaymentOrder created =
        PaymentOrder.pending(
            requested.orderId(),
            requested.userId(),
            requested.amount4(),
            requested.rateSnapshot4(),
            requested.rechargeType(),
            requested.createdAt());
    store.save(created);
    return created;
  }
}
