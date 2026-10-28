package com.xzwl.cloud.media.domain.task;

import java.time.Instant;

public record MediaTask(String id, String ownerId, String idempotencyKey, TaskStatus status,
    TaskData data, Instant createdAt, Instant deadlineAt, Instant nextRunAt, long version) {
  public boolean canTransitionTo(TaskStatus next) {
    if(data.request().operation()!=TaskOperation.DEPTH) return status.canTransitionTo(next);
    if(status.terminal()) return false;
    return next==status || next==TaskStatus.FAILED || next==TaskStatus.TIMED_OUT
        || status==TaskStatus.ROUTING && next==TaskStatus.RUNNING
        || status==TaskStatus.RUNNING && next==TaskStatus.ARCHIVING
        || status==TaskStatus.ARCHIVING && next==TaskStatus.SUCCEEDED;
  }
  public MediaTask advance(TaskStatus next, TaskData payload, Instant nextRun) {
    if (!canTransitionTo(next)) throw new IllegalStateException("illegal media task transition");
    return new MediaTask(id,ownerId,idempotencyKey,next,payload,createdAt,deadlineAt,nextRun,version+1);
  }
}
