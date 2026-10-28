package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable terminal result with per-image outcomes and failed-image refund. */
public record ImageBatchOutcome(
    ImageBatchStatus status,
    List<ImageItemOutcome> items,
    BigDecimal refundAmount) {

  public ImageBatchOutcome {
    status = Objects.requireNonNull(status, "status");
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    refundAmount = Objects.requireNonNull(refundAmount, "refundAmount");
    if (refundAmount.signum() < 0) {
      throw new IllegalArgumentException("refundAmount must not be negative");
    }
  }
}
