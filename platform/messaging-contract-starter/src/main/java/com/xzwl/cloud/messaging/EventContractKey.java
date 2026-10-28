package com.xzwl.cloud.messaging;

import java.util.Objects;

public record EventContractKey(String eventType, int eventVersion) {

    public EventContractKey {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be greater than zero");
        }
    }
}
