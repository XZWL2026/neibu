package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;

/** Provider port for an idempotent refund operation. */
public interface PaymentRefundPort {

  void refund(PaymentOrder order, String refundId);
}
