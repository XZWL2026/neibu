package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.EventId;
import com.xzwl.cloud.messaging.OutboxRecord;
import com.xzwl.cloud.messaging.OutboxStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryOutboxStore implements OutboxStore {

    private final Map<EventId, OutboxRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized void save(OutboxRecord.Pending record) {
        Objects.requireNonNull(record, "record must not be null");
        if (records.putIfAbsent(record.eventId(), record) != null) {
            throw new IllegalStateException("outbox event already exists: " + record.eventId().value());
        }
    }

    @Override
    public synchronized List<OutboxRecord.Pending> findPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        List<OutboxRecord.Pending> pending = new ArrayList<>();
        for (OutboxRecord record : records.values()) {
            if (record instanceof OutboxRecord.Pending pendingRecord) {
                pending.add(pendingRecord);
                if (pending.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(pending);
    }

    @Override
    public synchronized void markPublished(OutboxRecord.Published record) {
        Objects.requireNonNull(record, "record must not be null");
        OutboxRecord current = records.get(record.eventId());
        if (!(current instanceof OutboxRecord.Pending) || !current.envelope().equals(record.envelope())) {
            throw new IllegalStateException("matching pending outbox event does not exist: " + record.eventId().value());
        }
        records.put(record.eventId(), record);
    }

    public synchronized Optional<OutboxRecord> find(EventId eventId) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(eventId, "eventId must not be null")));
    }
}
