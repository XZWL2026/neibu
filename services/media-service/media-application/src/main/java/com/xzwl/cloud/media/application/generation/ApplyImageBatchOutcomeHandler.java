package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Computes partial-success status and refunds only failed image units. */
public final class ApplyImageBatchOutcomeHandler {

  private static final int BILLING_SCALE = 4;

  public ImageBatchOutcome handle(ApplyImageBatchOutcomeCommand command) {
    ApplyImageBatchOutcomeCommand requested = Objects.requireNonNull(command, "command");
    if (requested.items().isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }

    Set<Integer> indexes = new HashSet<>();
    int successfulCount = 0;
    for (ImageItemOutcome item : requested.items()) {
      Objects.requireNonNull(item, "item");
      if (!indexes.add(item.index())) {
        throw new IllegalArgumentException("duplicate image index");
      }
      if (item.succeeded()) {
        successfulCount++;
      }
    }

    int failedCount = requested.items().size() - successfulCount;
    ImageBatchStatus status =
        successfulCount == requested.items().size()
            ? ImageBatchStatus.SUCCEEDED
            : successfulCount == 0 ? ImageBatchStatus.FAILED : ImageBatchStatus.PARTIAL_SUCCESS;
    BigDecimal refundAmount =
        requested.pricePerImage()
            .multiply(BigDecimal.valueOf(failedCount))
            .setScale(BILLING_SCALE, RoundingMode.DOWN);
    return new ImageBatchOutcome(status, requested.items(), refundAmount);
  }
}
