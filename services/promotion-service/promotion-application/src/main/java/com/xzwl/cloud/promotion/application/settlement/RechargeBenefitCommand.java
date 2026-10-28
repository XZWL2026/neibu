package com.xzwl.cloud.promotion.application.settlement;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

public record RechargeBenefitCommand(
    String sourceEventId,
    String orderId,
    String fundingLotId,
    String walletOwnerUserId,
    Instant orderCreatedAt,
    long referralBindingVersion,
    String promoterUserId,
    Decimal4 paidAmount4) {

  public static RechargeBenefitCommand cashOnly(
      String sourceEventId, String orderId, String fundingLotId, String walletOwnerUserId,
      Instant orderCreatedAt, long referralBindingVersion, String promoterUserId,
      Decimal4 paidAmount4) {
    return new RechargeBenefitCommand(sourceEventId, orderId, fundingLotId, walletOwnerUserId,
        orderCreatedAt, referralBindingVersion, promoterUserId, paidAmount4);
  }
}
