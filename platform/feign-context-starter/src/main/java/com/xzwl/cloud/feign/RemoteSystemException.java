package com.xzwl.cloud.feign;

import java.util.Map;
import java.util.Objects;

public final class RemoteSystemException extends RuntimeException {
    public static final String DEFAULT_CODE = "REMOTE_SYSTEM_ERROR";

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final String requestId;
    private final Map<String, Object> details;
    private final String methodKey;

    public RemoteSystemException(
        int status,
        String code,
        String requestId,
        Map<String, Object> details,
        String methodKey
    ) {
        this(status, code, requestId, details, MethodKeyNormalizer.normalize(methodKey));
    }

    private RemoteSystemException(
        int status,
        String code,
        String requestId,
        Map<String, Object> details,
        MethodKeyNormalizer.Result methodKey
    ) {
        super(message(status, methodKey.value()));
        this.status = status;
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.requestId = requestId;
        this.details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
        this.methodKey = methodKey.value();
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    public Map<String, Object> details() {
        return details;
    }

    public String methodKey() {
        return methodKey;
    }

    private static String message(int status, String methodKey) {
        return "Remote system call failed [methodKey=" + methodKey + ", status=" + status + "]";
    }
}
