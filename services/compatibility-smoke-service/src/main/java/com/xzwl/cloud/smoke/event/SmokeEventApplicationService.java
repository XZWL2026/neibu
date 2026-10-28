package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.MessageEnvelope;
import java.util.Objects;

public final class SmokeEventApplicationService {

    private final InMemorySmokeTransaction transaction;

    public SmokeEventApplicationService(InMemorySmokeTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    public void recordSmoke(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (!"SmokeOccurred".equals(envelope.eventType()) || envelope.eventVersion() != 1) {
            throw new IllegalArgumentException("expected SmokeOccurred v1 envelope");
        }
        transaction.record(envelope);
    }
}
