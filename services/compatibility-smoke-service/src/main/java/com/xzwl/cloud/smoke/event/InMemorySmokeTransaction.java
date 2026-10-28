package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.MessageEnvelope;
import com.xzwl.cloud.messaging.OutboxRecord;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemorySmokeTransaction {

    private final InMemoryOutboxStore outboxStore;
    private final Runnable afterBusinessWrite;
    private final Map<String, MessageEnvelope> recordedEnvelopes = new HashMap<>();

    public InMemorySmokeTransaction(InMemoryOutboxStore outboxStore) {
        this(outboxStore, () -> {});
    }

    InMemorySmokeTransaction(InMemoryOutboxStore outboxStore, Runnable afterBusinessWrite) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.afterBusinessWrite = Objects.requireNonNull(afterBusinessWrite, "afterBusinessWrite must not be null");
    }

    public void record(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        synchronized (outboxStore) {
            String aggregateId = envelope.aggregateId();
            boolean previouslyRecorded = recordedEnvelopes.containsKey(aggregateId);
            MessageEnvelope previous = recordedEnvelopes.put(aggregateId, envelope);
            try {
                afterBusinessWrite.run();
                outboxStore.save(OutboxRecord.pending(envelope));
            } catch (RuntimeException | Error failure) {
                if (previouslyRecorded) {
                    recordedEnvelopes.put(aggregateId, previous);
                } else {
                    recordedEnvelopes.remove(aggregateId);
                }
                throw failure;
            }
        }
    }

    public Optional<MessageEnvelope> findRecordedEnvelope(String aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        synchronized (outboxStore) {
            return Optional.ofNullable(recordedEnvelopes.get(aggregateId));
        }
    }
}
