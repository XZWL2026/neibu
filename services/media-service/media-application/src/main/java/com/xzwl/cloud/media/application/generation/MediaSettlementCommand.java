package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Provider-neutral terminal billing facts, including partial image refunds. */
public record MediaSettlementCommand(
    String operationId, BigDecimal chargedAmount, Kind kind, BigDecimal refundAmount) {

  public MediaSettlementCommand {
    if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
    chargedAmount = normalizePositive(chargedAmount, "chargedAmount");
    kind = Objects.requireNonNull(kind, "kind");
    refundAmount = normalizeNonNegative(refundAmount, "refundAmount");
    if (refundAmount.compareTo(chargedAmount) > 0) throw new IllegalArgumentException("refundAmount exceeds chargedAmount");
    if (kind == Kind.SUCCEEDED && refundAmount.signum() != 0) throw new IllegalArgumentException("success cannot refund");
    if (kind == Kind.FAILED && refundAmount.compareTo(chargedAmount) != 0) throw new IllegalArgumentException("failure must refund the full charge");
  }

  public static MediaSettlementCommand success(String operationId, BigDecimal chargedAmount) {
    return new MediaSettlementCommand(operationId, chargedAmount, Kind.SUCCEEDED, BigDecimal.ZERO);
  }

  public static MediaSettlementCommand failure(String operationId, BigDecimal chargedAmount) {
    return new MediaSettlementCommand(operationId, chargedAmount, Kind.FAILED, chargedAmount);
  }

  public static MediaSettlementCommand partial(String operationId, BigDecimal chargedAmount, BigDecimal refundAmount) {
    return new MediaSettlementCommand(operationId, chargedAmount, Kind.PARTIAL_SUCCESS, refundAmount);
  }

  public enum Kind {
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED
  }

  private static BigDecimal normalizePositive(BigDecimal value, String name) {
    BigDecimal normalized = Objects.requireNonNull(value, name).setScale(4, RoundingMode.DOWN);
    if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return normalized;
  }

  private static BigDecimal normalizeNonNegative(BigDecimal value, String name) {
    BigDecimal normalized = Objects.requireNonNull(value, name).setScale(4, RoundingMode.DOWN);
    if (normalized.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    return normalized;
  }
}
