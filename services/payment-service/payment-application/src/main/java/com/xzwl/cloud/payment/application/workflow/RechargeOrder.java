package com.xzwl.cloud.payment.application.workflow;
import com.xzwl.cloud.payment.domain.order.*;
import java.math.BigDecimal;
import java.time.Instant;
public record RechargeOrder(String id,String userId,BigDecimal amount4,BigDecimal rateSnapshot4,
    RechargeType rechargeType,String enterpriseName,Instant createdAt,Instant expiresAt,String status,
    String transactionId,Instant paidAt,String providerOrderId,String checkoutUrl,boolean fulfilled,String keyId,String refundReference,String source,String remark,String submittedBy,String confirmedBy,PaymentMethod paymentMethod,String rejectedBy,Instant rejectedAt,Instant refundedAt) {
  public PaymentOrder domain(){return PaymentOrder.restore(id,userId,amount4,rateSnapshot4,rechargeType,createdAt,
      PaymentOrderStatus.valueOf(status),transactionId,refundReference);}
}
