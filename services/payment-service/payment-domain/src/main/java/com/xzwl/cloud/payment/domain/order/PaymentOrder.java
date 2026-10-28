package com.xzwl.cloud.payment.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Payment order state machine independent of any provider wire contract. */
public final class PaymentOrder {

  private static final Duration VALIDITY = Duration.ofMinutes(15);

  private final String id;
  private final String userId;
  private final BigDecimal amount4;
  private final BigDecimal rateSnapshot4;
  private final RechargeType rechargeType;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final PaymentOrderStatus status;
  private final String confirmationId;
  private final String refundId;

  private PaymentOrder(
      String id,
      String userId,
      BigDecimal amount4,
      BigDecimal rateSnapshot4,
      RechargeType rechargeType,
      Instant createdAt,
      PaymentOrderStatus status,
      String confirmationId,
      String refundId) {
    this.id = requireId(id, "id");
    this.userId = requireId(userId, "userId");
    this.amount4 = normalizePositive(amount4, "amount4");
    this.rateSnapshot4 = normalizePositive(rateSnapshot4, "rateSnapshot4");
    if (this.amount4.multiply(this.rateSnapshot4).setScale(4, RoundingMode.DOWN).signum() <= 0) {
      throw new IllegalArgumentException("recharge must convert to at least 0.0001 points");
    }
    this.rechargeType = Objects.requireNonNull(rechargeType, "rechargeType");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = createdAt.plus(VALIDITY);
    this.status = Objects.requireNonNull(status, "status");
    this.confirmationId = confirmationId;
    this.refundId = refundId;
  }

  public static PaymentOrder pending(
      String id, String userId, BigDecimal amount4, BigDecimal rateSnapshot4,
      RechargeType rechargeType, Instant createdAt) {
    return new PaymentOrder(id, userId, amount4, rateSnapshot4, rechargeType, createdAt,
        PaymentOrderStatus.PENDING, null, null);
  }

  public String id() { return id; }
  public static PaymentOrder restore(String id,String userId,BigDecimal amount4,BigDecimal rateSnapshot4,
      RechargeType type,Instant createdAt,PaymentOrderStatus status,String confirmationId,String refundId) {
    return new PaymentOrder(id,userId,amount4,rateSnapshot4,type,createdAt,status,confirmationId,refundId);
  }
  public String userId() { return userId; }
  public BigDecimal amount4() { return amount4; }
  public BigDecimal rateSnapshot4() { return rateSnapshot4; }
  public RechargeType rechargeType() { return rechargeType; }
  public Instant createdAt() { return createdAt; }
  public Instant expiresAt() { return expiresAt; }
  public PaymentOrderStatus status() { return status; }

  public boolean isPendingAt(Instant now) {
    return status == PaymentOrderStatus.PENDING
        && Objects.requireNonNull(now, "now").isBefore(expiresAt);
  }

  public PaymentOrder confirmPaid(String confirmationId, Instant confirmedAt) {
    PaymentOrder result = confirmAt(confirmationId, confirmedAt);
    if (result.status != PaymentOrderStatus.PAID) {
      throw new IllegalStateException("payment arrived after expiry and requires refund");
    }
    return result;
  }

  public PaymentOrder confirmAt(String confirmationId, Instant confirmedAt) {
    String confirmation = requireId(confirmationId, "confirmationId");
    Instant confirmed = Objects.requireNonNull(confirmedAt, "confirmedAt");
    if (confirmed.isBefore(createdAt)) {
      throw new IllegalArgumentException("confirmedAt must not be before createdAt");
    }
    if (status == PaymentOrderStatus.PAID
        || status == PaymentOrderStatus.REFUND_PENDING
        || status == PaymentOrderStatus.REFUNDED) {
      if (confirmation.equals(this.confirmationId)) return this;
      throw new IllegalStateException("payment order terminal confirmation conflicts");
    }
    PaymentOrderStatus next = confirmed.isBefore(expiresAt)
        ? PaymentOrderStatus.PAID
        : PaymentOrderStatus.REFUND_PENDING;
    return new PaymentOrder(id, userId, amount4, rateSnapshot4, rechargeType, createdAt, next, confirmation, null);
  }

  public PaymentOrder markRefunded(String refundId, Instant refundedAt) {
    String refund = requireId(refundId, "refundId");
    Instant refunded = Objects.requireNonNull(refundedAt, "refundedAt");
    if (refunded.isBefore(createdAt)) {
      throw new IllegalArgumentException("refundedAt must not be before createdAt");
    }
    if (status == PaymentOrderStatus.REFUNDED) {
      if (refund.equals(this.refundId)) return this;
      throw new IllegalStateException("payment refund conflicts with the original refund");
    }
    if (status != PaymentOrderStatus.REFUND_PENDING) {
      throw new IllegalStateException("only a refund-pending order can be refunded");
    }
    return new PaymentOrder(id, userId, amount4, rateSnapshot4, rechargeType, createdAt,
        PaymentOrderStatus.REFUNDED, confirmationId, refund);
  }

  private static BigDecimal normalizePositive(BigDecimal value, String name) {
    BigDecimal normalized = Objects.requireNonNull(value, name).setScale(4, RoundingMode.DOWN);
    if (normalized.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return normalized;
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }
}
