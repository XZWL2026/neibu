package com.xzwl.cloud.billing.application.ledger;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.util.List;
/** Each mutation commits its idempotency record, allocations and balance in one local transaction. */
public interface BillingLedgerStore {
  WalletBalance balance(String walletId);
  WalletBalance debit(Debit command);
  List<Adjustment> adjustments(String keyId);
  record Debit(String requestId, String keyId, String userId, String operatorId, java.math.BigDecimal amount4, String remark) {}
  record Adjustment(String requestId, String kind, String operatorId, String userId, String keyId,
      String amount4, String rateSnapshot4, String points4, String remark, String recordedAt, String balanceAfter4) {}
  WalletBalance consume(String walletId, String operationId, Decimal4 points);
  WalletBalance refund(String walletId, String refundId, String operationId, Decimal4 points);
  WalletBalance reserveRechargeRefund(String orderId);
  List<Entry> entries(String walletId, boolean administrator, int limit);
  /** amount4 is the signed historical CNY charge for CONSUME; null when no charge receipt exists. */
  record Entry(String requestId, String kind, String points4, String balanceAfter4, String recordedAt, String amount4) {}
}
