package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.PaymentOrder;
import java.util.List;

/** Persistence port for pending recharge-order lookup and creation. */
public interface PaymentOrderCreationStore {

  List<PaymentOrder> findPendingByUser(String userId);

  void save(PaymentOrder order);
}
