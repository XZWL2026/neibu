package com.xzwl.cloud.media.application.worker;

/** Persistence port for one worker lease per work item. */
public interface MediaWorkerLeaseStore {

  MediaWorkerLease load(String workId);

  void save(String workId, MediaWorkerLease lease);
}
