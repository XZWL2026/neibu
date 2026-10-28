package com.xzwl.cloud.feign;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class StandardFeignErrorDecoder implements ErrorDecoder {
    public static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    public static final int MAX_JSON_NESTING_DEPTH = 64;

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "code",
        "message",
        "requestId",
        "details"
    );

    private final ObjectMapper objectMapper;

    public StandardFeignErrorDecoder() {
        this(new ObjectMapper());
    }

    public StandardFeignErrorDecoder(ObjectMapper objectMapper) {
        ObjectMapper privateObjectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        ).copy();
        privateObjectMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .build()
        );
        this.objectMapper = privateObjectMapper
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        Objects.requireNonNull(response, "response must not be null");
        String effectiveMethodKey = MethodKeyNormalizer.normalize(methodKey).value();
        DecodedEnvelope envelope = readEnvelope(response.body());
        if (envelope == null) {
            return new RemoteSystemException(
                response.status(),
                RemoteSystemException.DEFAULT_CODE,
                null,
                Map.of(),
                effectiveMethodKey
            );
        }
        if (response.status() >= 400 && response.status() < 500) {
            return new RemoteBusinessException(
                response.status(),
                envelope.code,
                envelope.requestId,
                envelope.details,
                effectiveMethodKey
            );
        }
        return new RemoteSystemException(
            response.status(),
            envelope.code,
            envelope.requestId,
            envelope.details,
            effectiveMethodKey
        );
    }

    private DecodedEnvelope readEnvelope(Response.Body body) {
        if (body == null) {
            return null;
        }
        try (body) {
            Integer declaredLength = body.length();
            if (declaredLength != null && (declaredLength <= 0 || declaredLength > MAX_ERROR_BODY_BYTES)) {
                return null;
            }
            try (InputStream input = body.asInputStream()) {
                byte[] bytes = input.readNBytes(MAX_ERROR_BODY_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_ERROR_BODY_BYTES) {
                    return null;
                }
                return parseEnvelope(bytes);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private DecodedEnvelope parseEnvelope(byte[] bytes) throws IOException {
        JsonNode root = objectMapper.readTree(bytes);
        if (root == null || !root.isObject() || root.size() != ENVELOPE_FIELDS.size()) {
            return null;
        }
        if (!ENVELOPE_FIELDS.stream().allMatch(root::has)) {
            return null;
        }

        JsonNode code = root.get("code");
        JsonNode message = root.get("message");
        JsonNode requestId = root.get("requestId");
        JsonNode details = root.get("details");
        if (!isNonBlankText(code) || !isNonBlankText(message) || !isNonBlankText(requestId)) {
            return null;
        }
        if (!REQUEST_ID_PATTERN.matcher(requestId.textValue()).matches() || !details.isObject()) {
            return null;
        }

        Map<String, Object> detailValues = toPlainDetails(details);
        return new DecodedEnvelope(
            code.textValue(),
            requestId.textValue(),
            detailValues
        );
    }

    private static Map<String, Object> toPlainDetails(JsonNode details) {
        return toPlainObject(details, 2, false);
    }

    private static Map<String, Object> toPlainObject(
        JsonNode object,
        int depth,
        boolean allowDirectNull
    ) {
        requireAllowedDepth(depth);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!allowDirectNull && field.getValue().isNull()) {
                throw new IllegalArgumentException("Direct null is not allowed in error details");
            }
            values.put(field.getKey(), toPlainJsonValue(field.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Object toPlainJsonValue(JsonNode node, int depth) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isArray()) {
            requireAllowedDepth(depth);
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                values.add(toPlainJsonValue(element, depth + 1));
            }
            return Collections.unmodifiableList(values);
        }
        if (node.isObject()) {
            return toPlainObject(node, depth, true);
        }
        throw new IllegalArgumentException("Unsupported JSON value in error details");
    }

    private static void requireAllowedDepth(int depth) {
        if (depth > MAX_JSON_NESTING_DEPTH) {
            throw new IllegalArgumentException("Error details exceed the JSON nesting limit");
        }
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank();
    }

    private static final class DecodedEnvelope {
        private final String code;
        private final String requestId;
        private final Map<String, Object> details;

        private DecodedEnvelope(String code, String requestId, Map<String, Object> details) {
            this.code = code;
            this.requestId = requestId;
            this.details = details;
        }
    }
}
