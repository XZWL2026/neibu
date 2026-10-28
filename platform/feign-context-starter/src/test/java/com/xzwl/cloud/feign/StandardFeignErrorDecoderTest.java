package com.xzwl.cloud.feign;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StandardFeignErrorDecoderTest {
    private static final String METHOD_KEY = "InventoryClient#reserve";
    private static final int REQUIRED_MAX_JSON_NESTING_DEPTH = 64;

    private final StandardFeignErrorDecoder decoder = new StandardFeignErrorDecoder(
        new ObjectMapper()
    );

    @Test
    void mapsTrustedFourField4xxEnvelopeToBusinessException() {
        String body = """
            {
              "code": "INVENTORY_REJECTED",
              "message": "sensitive remote display text",
              "requestId": "request-123",
              "details": {"sku": "SKU-1", "available": 2}
            }
            """;

        Exception decoded = decoder.decode(METHOD_KEY, response(422, body));

        assertThat(decoded).isInstanceOf(RemoteBusinessException.class);
        RemoteBusinessException exception = (RemoteBusinessException) decoded;
        assertThat(exception.status()).isEqualTo(422);
        assertThat(exception.code()).isEqualTo("INVENTORY_REJECTED");
        assertThat(exception.requestId()).isEqualTo("request-123");
        assertThat(exception.methodKey()).isEqualTo(METHOD_KEY);
        assertThat(exception.details()).containsEntry("sku", "SKU-1").containsEntry("available", 2);
        assertThat(exception.getMessage())
            .doesNotContain("sensitive remote display text")
            .doesNotContain(body)
            .doesNotContain("SKU-1");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void mapsTrusted5xxEnvelopeToSystemExceptionWithoutLeakingRemoteMessage() {
        String body = """
            {"code":"DOWNSTREAM_FAILURE","message":"SQL password secret",
             "requestId":"request-500","details":{"operation":"reserve"}}
            """;

        Exception decoded = decoder.decode(METHOD_KEY, response(503, body));

        assertThat(decoded).isInstanceOf(RemoteSystemException.class);
        RemoteSystemException exception = (RemoteSystemException) decoded;
        assertThat(exception.status()).isEqualTo(503);
        assertThat(exception.code()).isEqualTo("DOWNSTREAM_FAILURE");
        assertThat(exception.requestId()).isEqualTo("request-500");
        assertThat(exception.methodKey()).isEqualTo(METHOD_KEY);
        assertThat(exception.details()).containsEntry("operation", "reserve");
        assertThat(exception.getMessage())
            .doesNotContain("SQL")
            .doesNotContain("password")
            .doesNotContain("secret")
            .doesNotContain(body);
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void mapsNon4xxStatusToSystemExceptionEvenWhenEnvelopeIsTrusted() {
        String body = envelope("REDIRECTED", "redirect detail", "request-302", "{}");

        Exception decoded = decoder.decode(METHOD_KEY, response(302, body));

        assertThat(decoded).isInstanceOf(RemoteSystemException.class);
        RemoteSystemException exception = (RemoteSystemException) decoded;
        assertThat(exception.status()).isEqualTo(302);
        assertThat(exception.code()).isEqualTo("REDIRECTED");
        assertThat(exception.requestId()).isEqualTo("request-302");
    }

    @Test
    void treatsPolymorphicTypeMetadataAsPlainDataWithoutMaterializingTypes() {
        MaterializationProbe.reset();
        ObjectMapper permissiveMapper = new ObjectMapper().activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );
        StandardFeignErrorDecoder permissiveDecoder = new StandardFeignErrorDecoder(
            permissiveMapper
        );
        String details = "{\"@class\":\"java.util.LinkedHashMap\",\"probe\":{"
            + "\"@class\":\"" + MaterializationProbe.class.getName() + "\","
            + "\"value\":\"remote-controlled\"}}";

        Exception decoded = permissiveDecoder.decode(
            METHOD_KEY,
            response(422, envelope("REJECTED", "remote", "request-typing", details))
        );

        assertThat(decoded).isInstanceOf(RemoteBusinessException.class);
        RemoteBusinessException exception = (RemoteBusinessException) decoded;
        assertThat(MaterializationProbe.constructions()).isZero();
        assertThat(exception.details().get("probe")).isInstanceOf(Map.class);
        assertThat(exception.details().get("probe")).isEqualTo(Map.of(
            "@class",
            MaterializationProbe.class.getName(),
            "value",
            "remote-controlled"
        ));
    }

    @Test
    void enforcesLocalNestingLimitWhenCallerObjectMapperAllowsDeeperJson() {
        assertThat(StandardFeignErrorDecoder.MAX_JSON_NESTING_DEPTH)
            .isEqualTo(REQUIRED_MAX_JSON_NESTING_DEPTH);
        ObjectMapper permissiveMapper = new ObjectMapper();
        permissiveMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(REQUIRED_MAX_JSON_NESTING_DEPTH + 32)
                .build()
        );
        StandardFeignErrorDecoder boundedDecoder = new StandardFeignErrorDecoder(
            permissiveMapper
        );
        String atLimitBody = envelope(
            "REJECTED",
            "remote",
            "request-at-limit",
            nestedDetails(REQUIRED_MAX_JSON_NESTING_DEPTH - 1)
        );
        String overLimitBody = envelope(
            "REJECTED",
            "remote",
            "request-over-limit",
            nestedDetails(REQUIRED_MAX_JSON_NESTING_DEPTH)
        );

        Exception atLimit = boundedDecoder.decode(METHOD_KEY, response(422, atLimitBody));
        Exception overLimit = boundedDecoder.decode(METHOD_KEY, response(422, overLimitBody));

        assertThat(atLimit).isInstanceOf(RemoteBusinessException.class);
        assertThat(overLimit).isInstanceOf(RemoteSystemException.class);
        assertThat(((RemoteSystemException) overLimit).code())
            .isEqualTo(RemoteSystemException.DEFAULT_CODE);
        assertThat(overLimitBody.getBytes(UTF_8))
            .hasSizeLessThan(StandardFeignErrorDecoder.MAX_ERROR_BODY_BYTES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void decodesNestedNullsFromWebErrorWireFixtureAsImmutablePlainData() throws Exception {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("nullable", null);
        List<Object> nestedList = new ArrayList<>();
        nestedList.add("value");
        nestedList.add(null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nestedMap", nestedMap);
        details.put("nestedList", nestedList);
        WebErrorEnvelopeWireFixture serverEnvelope = new WebErrorEnvelopeWireFixture(
            "INVENTORY_REJECTED",
            "remote display message",
            "request-nested-null",
            details
        );
        String body = new ObjectMapper().writeValueAsString(serverEnvelope);

        Exception decoded = decoder.decode(METHOD_KEY, response(422, body));

        assertThat(decoded).isInstanceOf(RemoteBusinessException.class);
        RemoteBusinessException exception = (RemoteBusinessException) decoded;
        Map<String, Object> decodedMap = (Map<String, Object>) exception.details().get(
            "nestedMap"
        );
        List<Object> decodedList = (List<Object>) exception.details().get("nestedList");
        assertThat(decodedMap).containsKey("nullable");
        assertThat(decodedMap.get("nullable")).isNull();
        assertThat(decodedList).containsExactly("value", null);
        assertThatThrownBy(() -> decodedMap.put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decodedList.add("new"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesUnsafeMethodKeysAcrossBusinessSystemAndFallbackPaths() {
        RemoteBusinessException business = (RemoteBusinessException) decoder.decode(
            "InventoryClient#reserve\r\nforged",
            response(409, envelope("REJECTED", "remote", "request-business", "{}"))
        );
        RemoteSystemException system = (RemoteSystemException) decoder.decode(
            "InventoryClient#reserve\tforged",
            response(503, envelope("DOWN", "remote", "request-system", "{}"))
        );
        RemoteSystemException fallback = (RemoteSystemException) decoder.decode(
            "InventoryClient#reserve\u2028forged\u2029",
            response(400, "not-json")
        );

        assertInvalidMethodKey(business.methodKey(), business.getMessage());
        assertInvalidMethodKey(system.methodKey(), system.getMessage());
        assertInvalidMethodKey(fallback.methodKey(), fallback.getMessage());
    }

    @Test
    void normalizesUnknownAndOversizedMethodKeysWhilePreservingNormalFeignKey() {
        RemoteBusinessException nullKey = (RemoteBusinessException) decoder.decode(
            null,
            response(409, envelope("REJECTED", "remote", "request-null", "{}"))
        );
        RemoteBusinessException blankKey = (RemoteBusinessException) decoder.decode(
            " \t ",
            response(409, envelope("REJECTED", "remote", "request-blank", "{}"))
        );
        RemoteBusinessException oversizedKey = (RemoteBusinessException) decoder.decode(
            "x".repeat(257),
            response(409, envelope("REJECTED", "remote", "request-long", "{}"))
        );
        RemoteBusinessException normalKey = (RemoteBusinessException) decoder.decode(
            METHOD_KEY,
            response(409, envelope("REJECTED", "remote", "request-normal", "{}"))
        );

        assertThat(nullKey.methodKey()).isEqualTo("<unknown>");
        assertThat(blankKey.methodKey()).isEqualTo("<unknown>");
        assertInvalidMethodKey(oversizedKey.methodKey(), oversizedKey.getMessage());
        assertThat(normalKey.methodKey()).isEqualTo(METHOD_KEY);
        assertThat(normalKey.getMessage()).contains(METHOD_KEY);
    }

    @Test
    void mapsEmptyMalformedAndUntrustedBodiesToSafeSystemException() {
        List<String> untrustedBodies = List.of(
            "",
            "not-json with database-password",
            "[]",
            "{\"code\":\"BAD\",\"message\":\"bad\",\"requestId\":\"request\"}",
            "{\"code\":\"BAD\",\"message\":\"bad\",\"requestId\":\"request\",\"details\":{},\"extra\":true}",
            "{\"code\":5,\"message\":\"bad\",\"requestId\":\"request\",\"details\":{}}",
            "{\"code\":\"BAD\",\"message\":false,\"requestId\":\"request\",\"details\":{}}",
            "{\"code\":\"BAD\",\"message\":\"bad\",\"requestId\":\"bad request id\",\"details\":{}}",
            "{\"code\":\"BAD\",\"message\":\"bad\",\"requestId\":\"request\",\"details\":[]}",
            "{\"code\":\"BAD\",\"code\":\"FORGED\",\"message\":\"bad\",\"requestId\":\"request\",\"details\":{}}",
            "{\"code\":\"BAD\",\"message\":\"bad\",\"requestId\":\"request\",\"details\":{\"direct\":null}}"
        );

        for (String body : untrustedBodies) {
            Exception decoded = decoder.decode(METHOD_KEY, response(400, body));

            assertThat(decoded).as(body).isInstanceOf(RemoteSystemException.class);
            RemoteSystemException exception = (RemoteSystemException) decoded;
            assertThat(exception.status()).isEqualTo(400);
            assertThat(exception.code()).isEqualTo("REMOTE_SYSTEM_ERROR");
            assertThat(exception.requestId()).isNull();
            assertThat(exception.details()).isEmpty();
            if (!body.isEmpty()) {
                assertThat(exception.getMessage()).doesNotContain(body);
            }
            assertThat(exception.getMessage())
                .doesNotContain("database-password")
                .doesNotContain("FORGED");
            assertThat(exception.getCause()).isNull();
        }
    }

    @Test
    void mapsMissingBodyToSafeSystemException() {
        Exception decoded = decoder.decode(METHOD_KEY, responseWithoutBody(404));

        assertThat(decoded).isInstanceOf(RemoteSystemException.class);
        RemoteSystemException exception = (RemoteSystemException) decoded;
        assertThat(exception.status()).isEqualTo(404);
        assertThat(exception.code()).isEqualTo("REMOTE_SYSTEM_ERROR");
        assertThat(exception.requestId()).isNull();
        assertThat(exception.details()).isEmpty();
    }

    @Test
    void boundsUnknownLengthBodyReadsAndClosesBodyAndStream() {
        byte[] oversized = "x".repeat(
            StandardFeignErrorDecoder.MAX_ERROR_BODY_BYTES + 100
        ).getBytes(UTF_8);
        TrackingBody body = new TrackingBody(oversized, null);

        Exception decoded = decoder.decode(METHOD_KEY, response(400, body));

        assertThat(decoded).isInstanceOf(RemoteSystemException.class);
        assertThat(body.bytesRead()).isLessThanOrEqualTo(
            StandardFeignErrorDecoder.MAX_ERROR_BODY_BYTES + 1
        );
        assertThat(body.bodyClosed()).isTrue();
        assertThat(body.streamClosed()).isTrue();
    }

    @Test
    void rejectsDeclaredOversizedBodyWithoutOpeningStreamAndStillClosesIt() {
        byte[] oversized = "x".repeat(
            StandardFeignErrorDecoder.MAX_ERROR_BODY_BYTES + 1
        ).getBytes(UTF_8);
        TrackingBody body = new TrackingBody(oversized, oversized.length);

        Exception decoded = decoder.decode(METHOD_KEY, response(413, body));

        assertThat(decoded).isInstanceOf(RemoteSystemException.class);
        assertThat(body.streamOpened()).isFalse();
        assertThat(body.bodyClosed()).isTrue();
    }

    @Test
    void closesValidResponseBodyAndInputStream() {
        byte[] bytes = envelope("REJECTED", "remote message", "request-close", "{}")
            .getBytes(UTF_8);
        TrackingBody body = new TrackingBody(bytes, bytes.length);

        Exception decoded = decoder.decode(METHOD_KEY, response(409, body));

        assertThat(decoded).isInstanceOf(RemoteBusinessException.class);
        assertThat(body.bodyClosed()).isTrue();
        assertThat(body.streamClosed()).isTrue();
    }

    @Test
    void businessExceptionDefensivelyCopiesAndFreezesTopLevelDetails() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("key", "original");
        RemoteBusinessException exception = new RemoteBusinessException(
            409,
            "REJECTED",
            "request-copy",
            mutable,
            METHOD_KEY
        );

        mutable.put("key", "changed");

        assertThat(exception.details()).containsEntry("key", "original");
        assertThatThrownBy(() -> exception.details().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void systemExceptionDefensivelyCopiesAndFreezesTopLevelDetails() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("key", "original");
        RemoteSystemException exception = new RemoteSystemException(
            503,
            "DOWNSTREAM_FAILURE",
            "request-copy",
            mutable,
            METHOD_KEY
        );

        mutable.put("key", "changed");

        assertThat(exception.details()).containsEntry("key", "original");
        assertThatThrownBy(() -> exception.details().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void publicExceptionConstructorsNormalizeUnsafeMethodKeys() {
        List<String> unsafeMethodKeys = List.of(
            "InventoryClient#reserve\r\nforged",
            "InventoryClient#reserve\tforged",
            "InventoryClient#reserve\u2028forged\u2029",
            "x".repeat(257)
        );

        for (String unsafeMethodKey : unsafeMethodKeys) {
            RemoteBusinessException business = new RemoteBusinessException(
                409,
                "REJECTED",
                "request-business",
                Map.of(),
                unsafeMethodKey
            );
            RemoteSystemException system = new RemoteSystemException(
                503,
                "DOWNSTREAM_FAILURE",
                "request-system",
                Map.of(),
                unsafeMethodKey
            );

            assertInvalidMethodKey(business.methodKey(), business.getMessage());
            assertInvalidMethodKey(system.methodKey(), system.getMessage());
        }
    }

    @Test
    void publicExceptionConstructorsNormalizeNullAndBlankMethodKeys() {
        RemoteBusinessException business = new RemoteBusinessException(
            409,
            "REJECTED",
            "request-business",
            Map.of(),
            null
        );
        RemoteSystemException system = new RemoteSystemException(
            503,
            "DOWNSTREAM_FAILURE",
            "request-system",
            Map.of(),
            " \t "
        );

        assertUnknownMethodKey(business.methodKey(), business.getMessage());
        assertUnknownMethodKey(system.methodKey(), system.getMessage());
    }

    private static void assertInvalidMethodKey(String methodKey, String message) {
        assertThat(methodKey).isEqualTo("<invalid>");
        assertThat(message)
            .contains("<invalid>")
            .doesNotContain("forged")
            .doesNotContain("\r")
            .doesNotContain("\n")
            .doesNotContain("\t")
            .doesNotContain("\u2028")
            .doesNotContain("\u2029");
    }

    private static void assertUnknownMethodKey(String methodKey, String message) {
        assertThat(methodKey).isEqualTo("<unknown>");
        assertThat(message).contains("<unknown>");
    }

    private static String envelope(
        String code,
        String message,
        String requestId,
        String details
    ) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message
            + "\",\"requestId\":\"" + requestId + "\",\"details\":" + details + "}";
    }

    private static String nestedDetails(int objectDepth) {
        String value = "\"leaf\"";
        for (int depth = 0; depth < objectDepth; depth++) {
            value = "{\"level\":" + value + "}";
        }
        return value;
    }

    private static Response response(int status, String body) {
        return Response.builder()
            .status(status)
            .reason("remote")
            .request(request())
            .body(body, UTF_8)
            .build();
    }

    private static Response response(int status, Response.Body body) {
        return Response.builder()
            .status(status)
            .reason("remote")
            .request(request())
            .body(body)
            .build();
    }

    private static Response responseWithoutBody(int status) {
        return Response.builder()
            .status(status)
            .reason("remote")
            .request(request())
            .build();
    }

    private static Request request() {
        return Request.create(
            Request.HttpMethod.GET,
            "https://inventory.example/reserve",
            Map.of(),
            null,
            UTF_8,
            new RequestTemplate()
        );
    }

    private static final class TrackingBody implements Response.Body {
        private final byte[] bytes;
        private final Integer declaredLength;
        private boolean bodyClosed;
        private boolean streamOpened;
        private boolean streamClosed;
        private int bytesRead;

        private TrackingBody(byte[] bytes, Integer declaredLength) {
            this.bytes = bytes;
            this.declaredLength = declaredLength;
        }

        @Override
        public Integer length() {
            return declaredLength;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public InputStream asInputStream() {
            streamOpened = true;
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) {
                        bytesRead++;
                    }
                    return value;
                }

                @Override
                public int read(byte[] target, int offset, int length) throws IOException {
                    int count = super.read(target, offset, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    streamClosed = true;
                    super.close();
                }
            };
        }

        @Override
        public Reader asReader(Charset charset) {
            return new InputStreamReader(asInputStream(), charset);
        }

        @Override
        public void close() {
            bodyClosed = true;
        }

        private boolean bodyClosed() {
            return bodyClosed;
        }

        private boolean streamOpened() {
            return streamOpened;
        }

        private boolean streamClosed() {
            return streamClosed;
        }

        private int bytesRead() {
            return bytesRead;
        }
    }

    public static final class MaterializationProbe {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        private String value;

        public MaterializationProbe() {
            CONSTRUCTIONS.incrementAndGet();
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        private static int constructions() {
            return CONSTRUCTIONS.get();
        }

        private static void reset() {
            CONSTRUCTIONS.set(0);
        }
    }

    private record WebErrorEnvelopeWireFixture(
        String code,
        String message,
        String requestId,
        Map<String, Object> details
    ) {
        private WebErrorEnvelopeWireFixture {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            Objects.requireNonNull(requestId, "requestId must not be null");
            details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
        }
    }
}
