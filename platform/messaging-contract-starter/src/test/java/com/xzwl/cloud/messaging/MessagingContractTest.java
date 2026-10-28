package com.xzwl.cloud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MessagingContractTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-26T12:00:00Z");

    @Test
    void envelopeUsesEventIdOnlyForDeduplicationAndAggregateIdOnlyForOrdering() {
        MessageEnvelope first = envelope("event-1", "aggregate-1");
        MessageEnvelope sameEventDifferentAggregate = envelope("event-1", "aggregate-2");
        MessageEnvelope differentEventSameAggregate = envelope("event-2", "aggregate-1");

        assertThat(first.deduplicationKey()).isEqualTo(new EventId("event-1"));
        assertThat(sameEventDifferentAggregate.deduplicationKey()).isEqualTo(first.deduplicationKey());
        assertThat(differentEventSameAggregate.deduplicationKey()).isNotEqualTo(first.deduplicationKey());
        assertThat(differentEventSameAggregate.orderingKey()).isEqualTo(first.orderingKey());
        assertThat(sameEventDifferentAggregate.orderingKey()).isNotEqualTo(first.orderingKey());

        AtomicInboxGuard guard = new AtomicInboxGuard();
        InboxGuard.Acquired acquired = assertAcquired(guard.begin(first.deduplicationKey()));
        assertThat(guard.complete(acquired)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);
        assertThat(guard.begin(sameEventDifferentAggregate.deduplicationKey()))
                .isEqualTo(new InboxGuard.Duplicate(first.deduplicationKey()));
    }

    @Test
    void eventTypeAndPositiveVersionFormTheDeserializationContractKey() {
        MessageEnvelope envelope = envelope("event-1", "aggregate-1");

        assertThat(envelope.contractKey()).isEqualTo(new EventContractKey("OrderCreated", 1));
        assertThat(new EventContractKey("OrderCreated", 2)).isNotEqualTo(envelope.contractKey());
        assertThat(new EventContractKey("OrderCancelled", 1)).isNotEqualTo(envelope.contractKey());
        assertThatThrownBy(() -> new EventContractKey(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventContractKey(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventContractKey("OrderCreated", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventContractKey("OrderCreated", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeHasExactIndependentWireFieldsAndDefensivelyCopiesPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", "order-1");

        MessageEnvelope envelope = new MessageEnvelope(
                "event-1",
                "OrderCreated",
                1,
                "aggregate-1",
                "Order",
                OCCURRED_AT,
                "request-1",
                payload);
        payload.put("injected", true);

        assertThat(Stream.of(MessageEnvelope.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly(
                        "eventId",
                        "eventType",
                        "eventVersion",
                        "aggregateId",
                        "aggregateType",
                        "occurredAt",
                        "requestId",
                        "payload");
        assertThat(envelope.payload()).containsExactly(Map.entry("orderId", "order-1"));
        assertThatThrownBy(() -> envelope.payload().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void envelopeRejectsNullBlankAndInvalidRoutingMetadata() {
        assertThatThrownBy(() -> copyEnvelope(null, "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope(" ", "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", null, 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "\t", 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 0, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", -1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, null, "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, " ", "Order", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", null, OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", " ", OCCURRED_AT,
                        "request-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", "Order", null,
                        "request-1", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> copyEnvelope("event-1", "OrderCreated", 1, "aggregate-1", "Order", OCCURRED_AT,
                        "request-1", mapWithNullValue()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eventIdValueRejectsNullAndBlank() {
        assertThatThrownBy(() -> new EventId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EventId("event-1").value()).isEqualTo("event-1");
    }

    @Test
    void inboxClaimIsAtomicAndCompletionMakesOnlyThatEventIdDuplicate() {
        AtomicInboxGuard guard = new AtomicInboxGuard();
        EventId eventId = new EventId("event-1");

        InboxGuard.Acquired first = assertAcquired(guard.begin(eventId));
        assertThat(guard.begin(eventId)).isEqualTo(new InboxGuard.InProgress(eventId));

        assertThat(guard.complete(first)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);

        assertThat(guard.begin(eventId)).isEqualTo(new InboxGuard.Duplicate(eventId));
        assertThat(guard.complete(first)).isEqualTo(InboxGuard.ClaimResolution.STALE);
        assertThat(guard.begin(new EventId("event-2"))).isInstanceOf(InboxGuard.Acquired.class);
    }

    @Test
    void inboxAbortAllowsRetryAndStaleClaimCannotCompleteNewAttempt() {
        AtomicInboxGuard guard = new AtomicInboxGuard();
        EventId eventId = new EventId("event-1");
        InboxGuard.Acquired failedAttempt = assertAcquired(guard.begin(eventId));

        assertThat(guard.abort(failedAttempt)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);
        InboxGuard.Acquired retry = assertAcquired(guard.begin(eventId));

        assertThat(retry.claimToken()).isNotEqualTo(failedAttempt.claimToken());
        assertThat(guard.complete(failedAttempt)).isEqualTo(InboxGuard.ClaimResolution.STALE);
        assertThat(guard.abort(failedAttempt)).isEqualTo(InboxGuard.ClaimResolution.STALE);
        assertThat(guard.complete(retry)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);
        assertThat(guard.begin(eventId)).isEqualTo(new InboxGuard.Duplicate(eventId));
    }

    @Test
    void concurrentBeginForOneEventIdAcquiresExactlyOneClaim() throws Exception {
        AtomicInboxGuard guard = new AtomicInboxGuard();
        EventId eventId = new EventId("event-1");
        int contenders = 8;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<InboxGuard.BeginResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
            for (int index = 0; index < contenders; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return guard.begin(eventId);
                }));
            }
            ready.await();
            start.countDown();

            List<InboxGuard.BeginResult> results = new ArrayList<>();
            for (Future<InboxGuard.BeginResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(InboxGuard.Acquired.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(InboxGuard.InProgress.class::isInstance).hasSize(contenders - 1);
        }
    }

    @Test
    void differentEventIdsRemainProcessableEvenWhenAggregateOrderingKeyMatches() {
        AtomicInboxGuard guard = new AtomicInboxGuard();
        MessageEnvelope first = envelope("event-1", "aggregate-1");
        MessageEnvelope second = envelope("event-2", "aggregate-1");

        assertThat(first.orderingKey()).isEqualTo(second.orderingKey());
        assertThat(guard.begin(first.deduplicationKey())).isInstanceOf(InboxGuard.Acquired.class);
        assertThat(guard.begin(second.deduplicationKey())).isInstanceOf(InboxGuard.Acquired.class);
    }

    @Test
    void outboxStartsPendingAndOnlyConfirmationProducesPublishedValue() {
        OutboxRecord.Pending pending = OutboxRecord.pending(envelope("event-1", "aggregate-1"));

        assertThat(pending.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.recordPublishResult(PublishResult.FAILED)).isSameAs(pending);
        assertThat(pending.status()).isEqualTo(OutboxStatus.PENDING);

        OutboxRecord publishedRecord = pending.recordPublishResult(PublishResult.CONFIRMED);

        assertThat(publishedRecord).isInstanceOf(OutboxRecord.Published.class).isNotSameAs(pending);
        OutboxRecord.Published published = (OutboxRecord.Published) publishedRecord;
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(pending.status()).isEqualTo(OutboxStatus.PENDING);
        assertThatThrownBy(() -> published.recordPublishResult(PublishResult.CONFIRMED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.recordPublishResult(PublishResult.FAILED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.recordPublishResult(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void failedPublishingOrThrownPublisherErrorLeavesStoredRecordPendingForRetry() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxRecord.Pending pending = OutboxRecord.pending(envelope("event-1", "aggregate-1"));
        store.save(pending);

        EventPublisher rejected = ignored -> PublishResult.FAILED;
        publishOnce(store, rejected, pending);

        List<OutboxRecord.Pending> retryableAfterRejection = store.findPending(10);
        assertThat(retryableAfterRejection).containsExactly(pending);

        EventPublisher throwsFailure = ignored -> {
            throw new IllegalStateException("broker unavailable");
        };
        assertThatThrownBy(() -> publishOnce(store, throwsFailure, pending))
                .isInstanceOf(IllegalStateException.class);
        List<OutboxRecord.Pending> retryableAfterException = store.findPending(10);
        assertThat(retryableAfterException).containsExactly(pending);

        EventPublisher confirmed = ignored -> PublishResult.CONFIRMED;
        publishOnce(store, confirmed, pending);

        assertThat(store.findPending(10)).isEmpty();
        assertThat(store.find(pending.eventId()).status()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void portsExposeSeparatedLifecycleResponsibilitiesWithoutAggregateDeduplication() throws Exception {
        assertThat(InboxGuard.class.getDeclaredMethod("begin", EventId.class).getReturnType())
                .isEqualTo(InboxGuard.BeginResult.class);
        assertThat(InboxGuard.class.getDeclaredMethod("complete", InboxGuard.Acquired.class).getReturnType())
                .isEqualTo(InboxGuard.ClaimResolution.class);
        assertThat(InboxGuard.class.getDeclaredMethod("abort", InboxGuard.Acquired.class).getReturnType())
                .isEqualTo(InboxGuard.ClaimResolution.class);
        assertThat(OutboxStore.class.getDeclaredMethod("save", OutboxRecord.Pending.class)).isNotNull();
        assertThat(OutboxStore.class.getDeclaredMethod("findPending", int.class).getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.xzwl.cloud.messaging.OutboxRecord$Pending>");
        assertThat(OutboxStore.class.getDeclaredMethod("markPublished", OutboxRecord.Published.class)).isNotNull();
        assertThat(EventPublisher.class.getDeclaredMethod("publish", MessageEnvelope.class).getReturnType())
                .isEqualTo(PublishResult.class);

        assertThat(Stream.of(InboxGuard.class.getDeclaredMethods())
                        .flatMap(method -> Stream.of(method.getParameterTypes())))
                .doesNotContain(String.class, MessageEnvelope.class);
    }

    @Test
    void productionContractsAreImmutableAndHaveNoDatabaseMapperOrSpringCoupling() throws IOException {
        List<Class<?>> contractTypes = List.of(
                EventId.class,
                EventContractKey.class,
                MessageEnvelope.class,
                OutboxRecord.class,
                OutboxRecord.Pending.class,
                OutboxRecord.Published.class,
                OutboxStatus.class,
                PublishResult.class,
                OutboxStore.class,
                InboxGuard.class,
                EventPublisher.class);

        for (Class<?> type : contractTypes) {
            assertThat(Stream.of(type.getAnnotations()).map(annotation -> annotation.annotationType().getName()))
                    .noneMatch(MessagingContractTest::isForbiddenFrameworkType);
            if (type == OutboxRecord.class) {
                assertThat(type.isSealed()).isTrue();
                assertThat(Modifier.isAbstract(type.getModifiers())).isTrue();
            } else if (!type.isInterface() && !type.isEnum()) {
                assertThat(Modifier.isFinal(type.getModifiers())).as(type.getName()).isTrue();
                assertThat(Stream.of(type.getDeclaredFields())
                                .filter(field -> !Modifier.isStatic(field.getModifiers())))
                        .allMatch(field -> Modifier.isFinal(field.getModifiers()));
            }
        }

        assertThat(Stream.of(OutboxRecord.Pending.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Stream.of(OutboxRecord.Published.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));

        Path mainSources = Path.of("src/main/java");
        String sourceText;
        try (Stream<Path> paths = Files.walk(mainSources)) {
            sourceText = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(MessagingContractTest::readSource)
                    .reduce("", (left, right) -> left + "\n" + right);
        }
        assertThat(sourceText)
                .doesNotContain(
                        "org.springframework",
                        "jakarta.persistence",
                        "javax.persistence",
                        "org.apache.ibatis",
                        "JpaRepository",
                        "CrudRepository",
                        "@Entity",
                        "@Table",
                        "CREATE TABLE",
                        "DataSource");
    }

    private static void publishOnce(
            InMemoryOutboxStore store, EventPublisher publisher, OutboxRecord.Pending pending) {
        PublishResult result = publisher.publish(pending.envelope());
        OutboxRecord afterPublish = pending.recordPublishResult(result);
        if (afterPublish instanceof OutboxRecord.Published published) {
            store.markPublished(published);
        }
    }

    private static MessageEnvelope envelope(String eventId, String aggregateId) {
        return new MessageEnvelope(
                eventId,
                "OrderCreated",
                1,
                aggregateId,
                "Order",
                OCCURRED_AT,
                "request-1",
                Map.of("orderId", "order-1"));
    }

    private static MessageEnvelope copyEnvelope(
            String eventId,
            String eventType,
            int eventVersion,
            String aggregateId,
            String aggregateType,
            Instant occurredAt,
            String requestId,
            Map<String, Object> payload) {
        return new MessageEnvelope(
                eventId, eventType, eventVersion, aggregateId, aggregateType, occurredAt, requestId, payload);
    }

    private static Map<String, Object> mapWithNullValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("invalid", null);
        return map;
    }

    private static InboxGuard.Acquired assertAcquired(InboxGuard.BeginResult result) {
        assertThat(result).isInstanceOf(InboxGuard.Acquired.class);
        return (InboxGuard.Acquired) result;
    }

    private static boolean isForbiddenFrameworkType(String typeName) {
        return typeName.startsWith("org.springframework")
                || typeName.startsWith("jakarta.persistence")
                || typeName.startsWith("javax.persistence")
                || typeName.startsWith("org.apache.ibatis");
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + path, exception);
        }
    }

    private static final class AtomicInboxGuard implements InboxGuard {

        private final Map<EventId, InboxEntry> entries = new HashMap<>();
        private final AtomicLong claimSequence = new AtomicLong();

        @Override
        public synchronized BeginResult begin(EventId eventId) {
            Objects.requireNonNull(eventId, "eventId must not be null");
            InboxEntry current = entries.get(eventId);
            if (current == null) {
                String token = "claim-" + claimSequence.incrementAndGet();
                entries.put(eventId, new InboxEntry(InboxEntryStatus.IN_PROGRESS, token));
                return new Acquired(eventId, token);
            }
            if (current.status() == InboxEntryStatus.IN_PROGRESS) {
                return new InProgress(eventId);
            }
            return new Duplicate(eventId);
        }

        @Override
        public synchronized ClaimResolution complete(Acquired claim) {
            InboxEntry current = currentClaim(claim);
            if (current == null) {
                return ClaimResolution.STALE;
            }
            entries.put(claim.eventId(), new InboxEntry(InboxEntryStatus.COMPLETED, current.claimToken()));
            return ClaimResolution.APPLIED;
        }

        @Override
        public synchronized ClaimResolution abort(Acquired claim) {
            if (currentClaim(claim) == null) {
                return ClaimResolution.STALE;
            }
            entries.remove(claim.eventId());
            return ClaimResolution.APPLIED;
        }

        private InboxEntry currentClaim(Acquired claim) {
            Objects.requireNonNull(claim, "claim must not be null");
            InboxEntry current = entries.get(claim.eventId());
            if (current == null
                    || current.status() != InboxEntryStatus.IN_PROGRESS
                    || !current.claimToken().equals(claim.claimToken())) {
                return null;
            }
            return current;
        }
    }

    private enum InboxEntryStatus {
        IN_PROGRESS,
        COMPLETED
    }

    private record InboxEntry(InboxEntryStatus status, String claimToken) {}

    private static final class InMemoryOutboxStore implements OutboxStore {

        private final Map<EventId, OutboxRecord> records = new HashMap<>();

        @Override
        public synchronized void save(OutboxRecord.Pending record) {
            Objects.requireNonNull(record, "record must not be null");
            records.put(record.eventId(), record);
        }

        @Override
        public synchronized List<OutboxRecord.Pending> findPending(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be greater than zero");
            }
            List<OutboxRecord.Pending> pending = new ArrayList<>();
            for (OutboxRecord record : records.values()) {
                if (record instanceof OutboxRecord.Pending pendingRecord && pending.size() < limit) {
                    pending.add(pendingRecord);
                }
            }
            return List.copyOf(pending);
        }

        @Override
        public synchronized void markPublished(OutboxRecord.Published record) {
            Objects.requireNonNull(records.get(record.eventId()), "outbox record must exist");
            records.put(record.eventId(), record);
        }

        private synchronized OutboxRecord find(EventId eventId) {
            return records.get(eventId);
        }
    }
}
