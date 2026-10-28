package com.xzwl.cloud.web;

import java.util.Map;
import java.util.Objects;

public record ErrorEnvelope(
    String code,
    String message,
    String requestId,
    Map<String, Object> details
) {
    public ErrorEnvelope {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }
}
