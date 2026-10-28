package com.xzwl.cloud.payment.infrastructure.workflow;
import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
public final class PaymentRows {
  private PaymentRows(){}
  public static class Proof { public String orderId,fileName,contentType; public Integer sizeBytes; public byte[] content; }
  @TableName("payment_order") public static class Order {
    @TableId(type=IdType.INPUT) public String id;
    public String userId,paymentMethod,rechargeType,enterpriseName,status,transactionId,paidAt,providerOrderId,checkoutUrl,keyId,refundReference,source,remark,submittedBy,confirmedBy,rejectedBy,rejectedAt,refundedAt;
    public BigDecimal amount4,rateSnapshot4;public Long createdAt,expiresAt;public Boolean fulfilled;
  }
  @TableName("payment_account") public static class Account {
    @TableId(type=IdType.INPUT) public String userId;
    public String lockedType,pendingRequestId;
    public Boolean rechargeCompleted;
  }
  @TableName("payment_account_change") public static class AccountChange {
    @TableId(type=IdType.INPUT) public String requestId;
    public String userId,accountType,operatorId;public Boolean createKey,completed;
  }
  @TableName("payment_request") public static class Request {
    @TableId(type=IdType.INPUT) public String requestId;
    public String userId,orderId;
  }
  public static class PromotionDelivery {
    public String orderId,occurredAt;public Long version;
  }
  @TableName("payment_work") public static class Work {
    @TableId(type=IdType.INPUT) public String orderId;
    public String stage,leaseToken,lastError;
    public Long leaseUntil,nextAttemptAt;public Integer attempts;public Boolean completed;
  }
}
