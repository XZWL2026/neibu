package com.xzwl.cloud.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record MessageEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        String aggregateType,
        Instant occurredAt,
        String requestId,
        Map<String, Object> payload) {

    public MessageEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");

        requireNonBlank(eventId, "eventId");
        requireNonBlank(eventType, "eventType");
        requireNonBlank(aggregateId, "aggregateId");
        requireNonBlank(aggregateType, "aggregateType");
        requireNonBlank(requestId, "requestId");
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be greater than zero");
        }

        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
    }

    public EventId deduplicationKey() {
        return new EventId(eventId);
    }

    public EventContractKey contractKey() {
        return new EventContractKey(eventType, eventVersion);
    }

    public String orderingKey() {
        return aggregateId;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
