package com.xzwl.cloud.promotion.infrastructure;

import com.xzwl.cloud.promotion.application.production.PromotionOperations;
import java.util.function.Function;

/** Acknowledges only after Billing's durable wallet receipt is returned. */
public final class HttpReferralRewardDelivery {
  private final PromotionOperations operations;
  private final Function<PromotionOperations.RewardDelivery,PromotionOperations.RewardReceipt> deliver;
  public HttpReferralRewardDelivery(PromotionOperations operations,Function<PromotionOperations.RewardDelivery,PromotionOperations.RewardReceipt> deliver) {this.operations=operations;this.deliver=deliver;}
  public void tick() {
    for(var command:operations.pendingRewards(50)) {
      try {
        var receipt=deliver.apply(command);
        if(receipt==null)throw new IllegalStateException("empty reward receipt");
        operations.rewardDelivered(command.eventId(),receipt);
      } catch(RuntimeException failure) {
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Reward delivery remains pending: {0}",command.eventId());
      }
    }
  }
}
