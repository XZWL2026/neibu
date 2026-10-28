package com.xzwl.cloud.promotion.domain.reward;

/** Whitelisted reasons; arbitrary operational text never belongs in the ledger. */
public enum PointRewardReason {
  TASK_SUCCESS_REWARD_GRANTED,
  TASK_SUCCESS_REWARD_REVERSED,
  REWARD_REVERSAL_SHORTFALL,
  AUTOMATIC_POINT_DEBT_OFFSET
}
