package com.xzwl.cloud.payment.application.order;

import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.Instant;

/** Application input for opening or reusing a provider-neutral recharge order. */
public record CreatePaymentOrderCommand(
    String orderId,
    String userId,
    BigDecimal amount4,
    BigDecimal rateSnapshot4,
    RechargeType rechargeType,
    Instant createdAt) {}
