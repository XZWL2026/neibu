package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.EventPublisher;
import com.xzwl.cloud.messaging.OutboxRecord;
import com.xzwl.cloud.messaging.OutboxStore;
import com.xzwl.cloud.messaging.PublishResult;
import java.util.Objects;

public final class SmokeOutboxDispatcher {

    private final OutboxStore outboxStore;
    private final EventPublisher publisher;

    public SmokeOutboxDispatcher(OutboxStore outboxStore, EventPublisher publisher) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public int dispatchPending(int limit) {
        int publishedCount = 0;
        for (OutboxRecord.Pending pending : outboxStore.findPending(limit)) {
            PublishResult result;
            try {
                result = publisher.publish(pending.envelope());
            } catch (RuntimeException publishFailure) {
                continue;
            }
            OutboxRecord next = pending.recordPublishResult(result);
            if (next instanceof OutboxRecord.Published published) {
                outboxStore.markPublished(published);
                publishedCount++;
            }
        }
        return publishedCount;
    }
}
