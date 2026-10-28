package com.xzwl.cloud.web;

import java.util.Map;
import java.util.Objects;

public final class BusinessException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public BusinessException(String code, String message, Map<String, Object> details) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
