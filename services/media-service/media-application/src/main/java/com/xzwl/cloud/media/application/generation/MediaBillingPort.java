package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;

/** Billing boundary used after media preparation has produced a fixed cost. */
public interface MediaBillingPort {

  void debit(String operationId, BigDecimal amount);

  void refund(String operationId, BigDecimal amount);
}
