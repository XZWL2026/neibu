package com.xzwl.cloud.payment.application.order;

import java.time.Instant;

/** Provider-neutral payment confirmation facts shared by callback and polling adapters. */
public record ConfirmPaymentCommand(String orderId, String confirmationId, Instant confirmedAt) {}
