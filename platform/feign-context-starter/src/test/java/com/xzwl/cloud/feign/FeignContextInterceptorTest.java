package com.xzwl.cloud.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class FeignContextInterceptorTest {
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String CALLER_SERVICE = "X-Caller-Service";
    private static final String SUBJECT_ID = "X-Subject-Id";
    private static final String SUBJECT_TYPE = "X-Subject-Type";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Test
    void propagatesExactlyFiveAllowlistedHeaders() {
        CallContext snapshot = new CallContext(
            "request-123",
            "order-service",
            "subject-456",
            "USER",
            "idempotency-789"
        );
        RequestTemplate template = new RequestTemplate();

        new FeignContextInterceptor(() -> snapshot).apply(template);

        assertThat(template.headers()).containsOnlyKeys(
            REQUEST_ID,
            CALLER_SERVICE,
            SUBJECT_ID,
            SUBJECT_TYPE,
            IDEMPOTENCY_KEY
        );
        assertThat(template.headers().get(REQUEST_ID)).containsExactly("request-123");
        assertThat(template.headers().get(CALLER_SERVICE)).containsExactly("order-service");
        assertThat(template.headers().get(SUBJECT_ID)).containsExactly("subject-456");
        assertThat(template.headers().get(SUBJECT_TYPE)).containsExactly("USER");
        assertThat(template.headers().get(IDEMPOTENCY_KEY)).containsExactly("idempotency-789");
    }

    @Test
    void omitsMissingAndBlankOptionalFieldsWithoutCreatingForbiddenHeaders() {
        CallContext snapshot = new CallContext(
            "request-123",
            "order-service",
            null,
            "  ",
            null
        );
        RequestTemplate template = new RequestTemplate();

        new FeignContextInterceptor(() -> snapshot).apply(template);

        assertThat(template.headers()).containsOnlyKeys(REQUEST_ID, CALLER_SERVICE);
        assertThat(template.headers()).doesNotContainKeys(
            "Cookie",
            "Authorization",
            "X-Trace-Id",
            "X-Internal-Secret",
            "Secret"
        );
    }

    @Test
    void readsASeparateSnapshotForEveryInvocation() {
        AtomicInteger providerCalls = new AtomicInteger();
        Supplier<CallContext> provider = () -> {
            int invocation = providerCalls.incrementAndGet();
            return new CallContext("request-" + invocation, "service-" + invocation, null, null, null);
        };
        FeignContextInterceptor interceptor = new FeignContextInterceptor(provider);
        RequestTemplate first = new RequestTemplate();
        RequestTemplate second = new RequestTemplate();

        interceptor.apply(first);
        interceptor.apply(second);

        assertThat(providerCalls).hasValue(2);
        assertThat(first.headers().get(REQUEST_ID)).containsExactly("request-1");
        assertThat(first.headers().get(CALLER_SERVICE)).containsExactly("service-1");
        assertThat(second.headers().get(REQUEST_ID)).containsExactly("request-2");
        assertThat(second.headers().get(CALLER_SERVICE)).containsExactly("service-2");
    }

    @Test
    void replacesExistingAllowlistedValuesWithOneSnapshotValue() {
        RequestTemplate template = new RequestTemplate();
        template.header(REQUEST_ID, "stale-1", "stale-2");
        template.header(SUBJECT_ID, "stale-subject");
        CallContext snapshot = new CallContext(
            "fresh-request",
            "fresh-service",
            "fresh-subject",
            "SERVICE",
            "fresh-key"
        );

        new FeignContextInterceptor(() -> snapshot).apply(template);

        assertThat(template.headers().get(REQUEST_ID)).containsExactly("fresh-request");
        assertThat(template.headers().get(SUBJECT_ID)).containsExactly("fresh-subject");
        assertThat(template.headers().values()).allSatisfy(values -> assertThat(values).hasSize(1));
    }

    @Test
    void removesStaleAllowlistedValuesWhenCurrentSnapshotOmitsThem() {
        RequestTemplate template = new RequestTemplate();
        template.header(SUBJECT_ID, "stale-subject");
        template.header(IDEMPOTENCY_KEY, "stale-key");

        new FeignContextInterceptor(
            () -> new CallContext("request", "service", null, null, null)
        ).apply(template);

        assertThat(template.headers()).containsOnlyKeys(REQUEST_ID, CALLER_SERVICE);
    }

    @Test
    void removesPreexistingForbiddenContextHeadersWithoutRemovingProtocolHeaders() {
        RequestTemplate template = new RequestTemplate();
        template.header("cookie", "session=browser-secret");
        template.header("AUTHORIZATION", "Bearer browser-token");
        template.header("X-Trace-Id", "trace-secret");
        template.header("x-internal-secret", "internal-secret");
        template.header("x-request-id", "case-variant-stale-request");
        template.header("Accept", "application/json");
        template.header("Content-Type", "application/json");

        new FeignContextInterceptor(
            () -> new CallContext("request", "service", null, null, null)
        ).apply(template);

        assertThat(template.headers()).containsOnlyKeys(
            REQUEST_ID,
            CALLER_SERVICE,
            "Accept",
            "Content-Type"
        );
        assertThat(template.headers().get(REQUEST_ID)).containsExactly("request");
        assertThat(template.headers().get("Accept")).containsExactly("application/json");
        assertThat(template.headers().get("Content-Type")).containsExactly("application/json");
    }

    @Test
    void callContextIsAnImmutableFiveStringRecordWithoutHeaderMapEscape() {
        assertThat(CallContext.class.isRecord()).isTrue();
        assertThat(Arrays.stream(CallContext.class.getRecordComponents()).map(component -> component.getName()))
            .containsExactly("requestId", "callerService", "subjectId", "subjectType", "idempotencyKey");
        assertThat(Arrays.stream(CallContext.class.getRecordComponents()).map(component -> component.getType().getName()))
            .containsOnly(String.class.getName());
        assertThat(Arrays.stream(CallContext.class.getDeclaredMethods()).map(method -> method.getReturnType().getName()))
            .doesNotContain(Map.class.getName());
        assertThat(Arrays.stream(CallContext.class.getDeclaredFields()))
            .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
    }

    @Test
    void rejectsHeaderInjectionControlsInEveryField() {
        for (int field = 0; field < 5; field++) {
            for (String malicious : List.of("value\rforged", "value\nforged", "value\u0000forged")) {
                int targetField = field;
                assertThatThrownBy(() -> contextWith(targetField, malicious))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("header-safe");
            }
        }
    }

    @Test
    void moduleHasNoGlobalContextRetryTimeoutOrAutoConfiguration() throws Exception {
        List<Class<?>> moduleTypes = List.of(
            CallContext.class,
            FeignContextInterceptor.class,
            StandardFeignErrorDecoder.class,
            RemoteBusinessException.class,
            RemoteSystemException.class
        );

        assertThat(moduleTypes).allSatisfy(type -> {
            assertThat(Retryer.class.isAssignableFrom(type)).isFalse();
            assertThat(Request.Options.class.isAssignableFrom(type)).isFalse();
            assertThat(Arrays.stream(type.getDeclaredFields()))
                .noneSatisfy(field -> assertThat(field.getType()).isEqualTo(ThreadLocal.class));
            assertThat(Arrays.stream(type.getDeclaredFields()).filter(field -> Modifier.isStatic(field.getModifiers())))
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
            assertThat(Arrays.stream(type.getDeclaredMethods()).map(method -> method.getReturnType().getName()))
                .doesNotContain(Retryer.class.getName(), Request.Options.class.getName());
        });
        assertThat(RequestInterceptor.class.isAssignableFrom(FeignContextInterceptor.class)).isTrue();

        Path classesRoot = Path.of(
            FeignContextInterceptor.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        assertThat(classesRoot.resolve(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        )).doesNotExist();
        assertThatThrownBy(() -> Class.forName(
            "org.springframework.boot.autoconfigure.AutoConfiguration",
            false,
            FeignContextInterceptor.class.getClassLoader()
        )).isInstanceOf(ClassNotFoundException.class);
    }

    private static CallContext contextWith(int targetField, String value) {
        String[] fields = {"request", "caller", "subject", "type", "idempotency"};
        fields[targetField] = value;
        return new CallContext(fields[0], fields[1], fields[2], fields[3], fields[4]);
    }
}
