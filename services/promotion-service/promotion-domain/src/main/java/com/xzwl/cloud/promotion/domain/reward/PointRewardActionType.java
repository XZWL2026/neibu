package com.xzwl.cloud.promotion.domain.reward;

/** Closed action vocabulary for the immutable referral point entitlement ledger. */
public enum PointRewardActionType {
  POINT_REWARD_GRANTED,
  POINT_REWARD_REVERSED,
  POINT_DEBT_CREATED,
  POINT_DEBT_OFFSET
}
