package com.xzwl.cloud.messaging;

import java.util.List;

public interface OutboxStore {

    /** Saves a newly created pending record in the service's local transaction. */
    void save(OutboxRecord.Pending record);

    List<OutboxRecord.Pending> findPending(int limit);

    /** Persists the published state produced only by a confirmed publisher result. */
    void markPublished(OutboxRecord.Published record);
}
