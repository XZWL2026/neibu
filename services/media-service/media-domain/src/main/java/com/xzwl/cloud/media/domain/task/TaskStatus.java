package com.xzwl.cloud.media.domain.task;

public enum TaskStatus {
  ROUTING, CHARGE_PENDING, SUBMIT_PENDING, RUNNING, ARCHIVING, REFUND_PENDING,
  SUCCEEDED, PARTIAL_SUCCESS, FAILED, TIMED_OUT;

  public boolean terminal() {
    return this == SUCCEEDED || this == PARTIAL_SUCCESS || this == FAILED || this == TIMED_OUT;
  }

  public boolean canTransitionTo(TaskStatus next) {
    if (terminal()) return false;
    if (next == this) return true;
    return switch (this) {
      case ROUTING -> next == CHARGE_PENDING || next == FAILED || next == TIMED_OUT;
      case CHARGE_PENDING -> next == SUBMIT_PENDING || next == FAILED || next == REFUND_PENDING;
      case SUBMIT_PENDING -> next == RUNNING || next == ARCHIVING || next == REFUND_PENDING;
      case RUNNING -> next == ARCHIVING || next == REFUND_PENDING;
      case ARCHIVING -> next == SUCCEEDED || next == REFUND_PENDING;
      case REFUND_PENDING -> next == FAILED || next == TIMED_OUT || next == PARTIAL_SUCCESS;
      default -> false;
    };
  }
}
