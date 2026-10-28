package com.xzwl.cloud.kernel;

public record RequestId(String value) {

    public RequestId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
    }

    public static RequestId of(String value) {
        return new RequestId(value);
    }
}
