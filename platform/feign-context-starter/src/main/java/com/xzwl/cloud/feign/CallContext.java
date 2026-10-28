package com.xzwl.cloud.feign;

public record CallContext(
    String requestId,
    String callerService,
    String subjectId,
    String subjectType,
    String idempotencyKey
) {
    public CallContext {
        requireHeaderSafe("requestId", requestId);
        requireHeaderSafe("callerService", callerService);
        requireHeaderSafe("subjectId", subjectId);
        requireHeaderSafe("subjectType", subjectType);
        requireHeaderSafe("idempotencyKey", idempotencyKey);
    }

    private static void requireHeaderSafe(String field, String value) {
        if (value == null) {
            return;
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be header-safe");
        }
    }
}
