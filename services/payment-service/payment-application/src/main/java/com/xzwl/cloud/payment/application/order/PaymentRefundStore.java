package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import java.util.Optional;

/** Persistence port for late-payment refund state transitions. */
public interface PaymentRefundStore {

  Optional<PaymentOrder> findById(String orderId);

  void save(PaymentOrder order);
}
