package com.xzwl.cloud.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DomainEventEnvelopeTest {

    @Test
    void envelopeHasTheExactFieldsAndCarriesStableRoutingAndDeduplicationValues() {
        var occurredAt = Instant.parse("2026-08-25T00:00:00Z");
        var payload = Map.<String, Object>of("userId", "user-1");

        var envelope =
                new DomainEventEnvelope(
                        "evt-1",
                        "UserRegistered",
                        1,
                        "user-1",
                        "User",
                        occurredAt,
                        "req-1",
                        payload);

        assertThat(DomainEventEnvelope.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "eventId",
                        "eventType",
                        "eventVersion",
                        "aggregateId",
                        "aggregateType",
                        "occurredAt",
                        "requestId",
                        "payload");
        assertThat(envelope.eventId()).isEqualTo("evt-1");
        assertThat(envelope.eventType()).isEqualTo("UserRegistered");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.aggregateId()).isEqualTo("user-1");
        assertThat(envelope.aggregateType()).isEqualTo("User");
        assertThat(envelope.occurredAt()).isEqualTo(occurredAt);
        assertThat(envelope.requestId()).isEqualTo("req-1");
        assertThat(envelope.payload()).containsExactlyEntriesOf(payload);
    }

    @Test
    void envelopeDefensivelyCopiesItsPayload() {
        var mutablePayload = new HashMap<String, Object>();
        mutablePayload.put("userId", "user-1");
        var mutableNestedValue = new ArrayList<>(List.of("registered"));
        mutablePayload.put("states", mutableNestedValue);
        var envelope =
                new DomainEventEnvelope(
                        "evt-1",
                        "UserRegistered",
                        1,
                        "user-1",
                        "User",
                        Instant.parse("2026-08-25T00:00:00Z"),
                        "req-1",
                        mutablePayload);

        mutablePayload.put("userId", "changed");
        mutablePayload.put("extra", true);
        mutableNestedValue.add("confirmed");

        assertThat(envelope.payload())
                .containsEntry("userId", "user-1")
                .doesNotContainKey("extra");
        assertThatThrownBy(() -> envelope.payload().put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(envelope.payload().get("states")).isSameAs(mutableNestedValue);
        assertThat(mutableNestedValue).containsExactly("registered", "confirmed");
    }

    @ParameterizedTest(name = "{0} rejects null")
    @MethodSource("requiredStringFields")
    void rejectsNullRoutingAndDeduplicationStrings(String field) {
        assertThatThrownBy(() -> envelopeWithStringField(field, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(field + " must not be null");
    }

    @ParameterizedTest(name = "{0} rejects blank")
    @MethodSource("requiredStringFields")
    void rejectsBlankRoutingAndDeduplicationStrings(String field) {
        assertThatThrownBy(() -> envelopeWithStringField(field, " \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(field + " must not be blank");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveEventVersion(int eventVersion) {
        assertThatThrownBy(() -> envelope(eventVersion, VALID_OCCURRED_AT, VALID_PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eventVersion must be greater than zero");
    }

    @Test
    void rejectsNullOccurredAt() {
        assertThatThrownBy(() -> envelope(1, null, VALID_PAYLOAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt must not be null");
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> envelope(1, VALID_OCCURRED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("payload must not be null");
    }

    @Test
    void rejectsNullPayloadKeysAndValues() {
        var nullKeyPayload = new HashMap<String, Object>();
        nullKeyPayload.put(null, "value");
        var nullValuePayload = new HashMap<String, Object>();
        nullValuePayload.put("key", null);

        assertThatThrownBy(() -> envelope(1, VALID_OCCURRED_AT, nullKeyPayload))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> envelope(1, VALID_OCCURRED_AT, nullValuePayload))
                .isInstanceOf(NullPointerException.class);
    }

    private static final Instant VALID_OCCURRED_AT =
            Instant.parse("2026-08-25T00:00:00Z");
    private static final Map<String, Object> VALID_PAYLOAD = Map.of("userId", "user-1");

    private static Stream<String> requiredStringFields() {
        return Stream.of("eventId", "eventType", "aggregateId", "aggregateType", "requestId");
    }

    private static DomainEventEnvelope envelopeWithStringField(String field, String value) {
        return new DomainEventEnvelope(
                field.equals("eventId") ? value : "evt-1",
                field.equals("eventType") ? value : "UserRegistered",
                1,
                field.equals("aggregateId") ? value : "user-1",
                field.equals("aggregateType") ? value : "User",
                VALID_OCCURRED_AT,
                field.equals("requestId") ? value : "req-1",
                VALID_PAYLOAD);
    }

    private static DomainEventEnvelope envelope(
            int eventVersion, Instant occurredAt, Map<String, Object> payload) {
        return new DomainEventEnvelope(
                "evt-1",
                "UserRegistered",
                eventVersion,
                "user-1",
                "User",
                occurredAt,
                "req-1",
                payload);
    }
}
