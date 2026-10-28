package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;

/** Billing settlement boundary for a terminal media outcome. */
public interface MediaSettlementPort {

  void settleSuccess(String operationId, BigDecimal chargedAmount);

  void refund(String operationId, BigDecimal refundAmount);
}
