package com.xzwl.cloud.messaging;

import java.util.Objects;

public record EventId(String value) {

    public EventId {
        Objects.requireNonNull(value, "eventId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
    }
}
