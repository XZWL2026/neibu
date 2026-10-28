package com.xzwl.cloud.payment.application.workflow;
import java.math.BigDecimal;
/** All remote writes use stable order identity and are safe to repeat after a response is lost. */
public interface RechargeFulfillmentPort {
  BigDecimal currentRate();
  default void manageAccount(PaymentWorkflowStore.AccountChange change){throw new IllegalStateException("account management unavailable");}
  default String existingAccountType(String owner){return null;}
  String ensurePrimary(RechargeOrder order);
  void credit(RechargeOrder order,String keyId);
  default void promote(PaymentWorkflowStore.PromotionDelivery delivery){throw new IllegalStateException("promotion delivery unavailable");}
  void reserveRefund(RechargeOrder order);
}
