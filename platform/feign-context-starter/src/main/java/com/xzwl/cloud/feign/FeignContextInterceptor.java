package com.xzwl.cloud.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class FeignContextInterceptor implements RequestInterceptor {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CALLER_SERVICE_HEADER = "X-Caller-Service";
    public static final String SUBJECT_ID_HEADER = "X-Subject-Id";
    public static final String SUBJECT_TYPE_HEADER = "X-Subject-Type";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final Supplier<CallContext> contextProvider;

    public FeignContextInterceptor(Supplier<CallContext> contextProvider) {
        this.contextProvider = Objects.requireNonNull(
            contextProvider,
            "contextProvider must not be null"
        );
    }

    @Override
    public void apply(RequestTemplate template) {
        Objects.requireNonNull(template, "template must not be null");
        CallContext context = Objects.requireNonNull(
            contextProvider.get(),
            "contextProvider must return a CallContext"
        );
        removeForbiddenContextHeaders(template);
        replace(template, REQUEST_ID_HEADER, context.requestId());
        replace(template, CALLER_SERVICE_HEADER, context.callerService());
        replace(template, SUBJECT_ID_HEADER, context.subjectId());
        replace(template, SUBJECT_TYPE_HEADER, context.subjectType());
        replace(template, IDEMPOTENCY_KEY_HEADER, context.idempotencyKey());
    }

    private static void removeForbiddenContextHeaders(RequestTemplate template) {
        for (String header : List.copyOf(template.headers().keySet())) {
            if (header.equalsIgnoreCase("Cookie")
                || header.equalsIgnoreCase("Authorization")
                || header.regionMatches(true, 0, "X-", 0, 2)) {
                template.removeHeader(header);
            }
        }
    }

    private static void replace(RequestTemplate template, String header, String value) {
        template.removeHeader(header);
        if (value != null && !value.isBlank()) {
            template.header(header, value);
        }
    }
}
