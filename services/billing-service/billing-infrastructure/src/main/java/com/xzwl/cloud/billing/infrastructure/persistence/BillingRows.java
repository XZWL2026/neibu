package com.xzwl.cloud.billing.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
public final class BillingRows {
  private BillingRows() {}
  @TableName("wallet") public static class Wallet {
    @TableId(type=IdType.INPUT) public String walletId;
    public BigDecimal availablePoints4; public Long version;
  }
  @TableName("admin_credit_record") public static class AdminCredit {
    @TableId(type=IdType.INPUT) public String requestId;
    public String walletId, keyId, adminId, recordedAt, remark, status;
    public BigDecimal points4, balanceAfter4; public Long balanceVersion;
  }
  @TableName("recharge_credit_record") public static class Recharge {
    @TableId(type=IdType.INPUT) public String orderId;
    public String userId, walletId, keyId, paidAt;
    public BigDecimal amount4, rateSnapshot4, points4, balanceAfter4; public Long balanceVersion;
  }
  @TableName("funding_lot") public static class Lot {
    @TableId(type=IdType.INPUT) public String lotId;
    public String walletId, lotType, creditedAt;
    public BigDecimal originalPoints4, remainingPoints4; public Boolean benefitEligible;
  }
  @TableName("recharge_conversion_rate") public static class Rate {
    @TableId(type=IdType.INPUT) public Integer id;
    public BigDecimal pointsPerYuan4; public Long version;
  }
  @TableName("wallet_ledger") public static class Ledger {
    @TableId(type=IdType.INPUT) public String entryId;
    public String walletId, requestId, kind, sourceId, recordedAt;
    public BigDecimal points4, balanceAfter4; public Long balanceVersion;
  }
  @TableName("admin_debit_record") public static class AdminDebit {
    @TableId(type=IdType.INPUT) public String requestId;
    public String keyId, userId, operatorId, remark, recordedAt;
    public BigDecimal amount4, rateSnapshot4, points4, balanceAfter4;
    public Long balanceVersion;
  }
  @TableName("funding_allocation") public static class Allocation {
    @TableId(type=IdType.INPUT) public String id;
    public String walletId, operationId, lotId, kind;
    public BigDecimal points4, refundedPoints4;
  }
}
