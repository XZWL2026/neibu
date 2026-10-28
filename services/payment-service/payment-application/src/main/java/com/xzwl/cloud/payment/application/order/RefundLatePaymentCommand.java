package com.xzwl.cloud.payment.application.order;

import java.time.Instant;

/** Application input for completing a refund after a late payment. */
public record RefundLatePaymentCommand(String orderId, String refundId, Instant refundedAt) {}
