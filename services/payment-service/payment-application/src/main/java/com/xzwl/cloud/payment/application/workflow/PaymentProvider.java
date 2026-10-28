package com.xzwl.cloud.payment.application.workflow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
/** Provider adapters must verify the original bytes and merchant/app identity before returning facts.
 * Create/refund MUST be idempotent for orderId/refundId, including ambiguous network timeouts.
 * refund returns only after provider-confirmed success; pending/unknown outcomes must remain retryable. */
public interface PaymentProvider {
  Checkout create(Charge charge);
  default void validateAmount(BigDecimal amount,String currency) {}
  default QueryResult query(Charge charge){return new QueryResult(Optional.empty(),false);}
  record QueryResult(Optional<VerifiedPayment> payment,boolean finalWithoutPayment){}
  default void validateCheckout(Checkout checkout){
    var uri=java.net.URI.create(checkout.checkoutUrl());
    if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
      throw new IllegalStateException("provider checkout requires HTTPS");
  }
  VerifiedPayment verify(byte[] rawBody,Map<String,List<String>> headers);
  String refund(Refund refund);
  record Charge(String orderId,BigDecimal amount4,String currency,Instant expiresAt,com.xzwl.cloud.payment.domain.order.PaymentMethod paymentMethod){
    public Charge(String orderId,BigDecimal amount4,String currency,Instant expiresAt){this(orderId,amount4,currency,expiresAt,null);}
  }
  /** A known provider order can have no recoverable QR after a lost response; polling must continue. */
  record Checkout(String providerOrderId,String checkoutUrl){}
  record VerifiedPayment(String orderId,String transactionId,BigDecimal amount4,String currency,Instant paidAt){}
  record Refund(String orderId,String transactionId,String refundId,BigDecimal amount4,String currency){}
  final class ChannelAmountLimit extends IllegalArgumentException {public ChannelAmountLimit(){super("PAYMENT_CHANNEL_AMOUNT_LIMIT");}}
  final class Unavailable extends RuntimeException {public Unavailable(){super("payment provider is not configured");} public Unavailable(String safeCode){super(safeCode);}}
}
