package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Application input for evaluating terminal outcomes of a multi-image request. */
public record ApplyImageBatchOutcomeCommand(
    List<ImageItemOutcome> items,
    BigDecimal pricePerImage) {

  public ApplyImageBatchOutcomeCommand {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    pricePerImage = Objects.requireNonNull(pricePerImage, "pricePerImage");
    if (pricePerImage.signum() < 0) {
      throw new IllegalArgumentException("pricePerImage must not be negative");
    }
  }
}
