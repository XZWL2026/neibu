package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.FirstRechargeTypeLock;
import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import java.util.Optional;

/** Persistence port for payment order state transitions. */
public interface PaymentOrderStore {

  Optional<PaymentOrder> findById(String orderId);

  FirstRechargeTypeLock loadFirstRechargeTypeLock(String userId);

  void save(PaymentOrder order);
}
