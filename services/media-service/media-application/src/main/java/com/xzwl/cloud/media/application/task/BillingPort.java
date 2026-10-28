package com.xzwl.cloud.media.application.task;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;

/** Billing owns pricing and wallets. Both operations must replay their original result by ID.
 * A timeout MUST NOT be translated to DefinitiveRejectionException. Charge replay must use the
 * original price/rate, even if configuration changed; refunds restore the original allocation.
 */
public interface BillingPort {
  default BigDecimal quote(String ownerId,TaskRequest request) { throw new DependencyUnavailableException("pricing unavailable"); }
  default java.util.List<AvailablePrice> availablePrices(String ownerId) { throw new DependencyUnavailableException("pricing unavailable"); }
  default EnhancementCost enhancementQuote(String ownerId,String target,int billableSeconds) { throw new DependencyUnavailableException("enhancement pricing unavailable"); }
  record EnhancementCost(BigDecimal amount,BigDecimal rate,BigDecimal points) {}
  record AvailablePrice(String model,String resolution,BigDecimal amountPerUnit,String type) {
    public AvailablePrice(String model,String resolution,BigDecimal amountPerUnit){this(model,resolution,amountPerUnit,null);}
  }
  record Settlement(String taskId,String ownerId,String status,String chargedPoints4,String refundedPoints4,java.time.Instant occurredAt) {}
  default void settle(Settlement settlement) { throw new DependencyUnavailableException("settlement unavailable"); }
  default ChargeReceipt chargeEnhancement(String id,String owner,String target,int seconds,EnhancementCost cost) { throw new DependencyUnavailableException("enhancement billing unavailable"); }
  ChargeReceipt charge(String operationId, String ownerId, TaskRequest request, RouteSnapshot route);
  void refund(String refundId, String ownerId, ChargeReceipt original, BigDecimal points);
}
