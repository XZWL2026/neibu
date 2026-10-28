package com.xzwl.cloud.smoke.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.messaging.EventId;
import com.xzwl.cloud.messaging.InboxGuard;
import com.xzwl.cloud.messaging.MessageEnvelope;
import com.xzwl.cloud.messaging.OutboxRecord;
import com.xzwl.cloud.messaging.OutboxStatus;
import com.xzwl.cloud.messaging.PublishResult;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmokeEventFlowTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-27T08:00:00Z");

    @Test
    void businessWriteAndPendingOutboxCommitAtomically() {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        InMemorySmokeTransaction transaction = new InMemorySmokeTransaction(outbox);
        SmokeEventApplicationService service = new SmokeEventApplicationService(transaction);
        MessageEnvelope envelope = envelope("event-1", "smoke-1");

        service.recordSmoke(envelope);

        assertThat(transaction.findRecordedEnvelope("smoke-1")).contains(envelope);
        assertThat(outbox.find(envelope.deduplicationKey()))
                .get()
                .extracting(OutboxRecord::status)
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void transactionFailureLeavesNeitherBusinessWriteNorOutboxRecord() {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        InMemorySmokeTransaction transaction = new InMemorySmokeTransaction(outbox, () -> {
            throw new IllegalStateException("simulated local transaction failure");
        });
        SmokeEventApplicationService service = new SmokeEventApplicationService(transaction);
        MessageEnvelope envelope = envelope("event-1", "smoke-1");

        assertThatThrownBy(() -> service.recordSmoke(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated local transaction failure");
        assertThat(transaction.findRecordedEnvelope("smoke-1")).isEmpty();
        assertThat(outbox.find(envelope.deduplicationKey())).isEmpty();
    }

    @Test
    void confirmedPublishMarksTheStoredRecordPublished() {
        InMemoryOutboxStore outbox = pendingOutbox(envelope("event-1", "smoke-1"));
        SmokeOutboxDispatcher dispatcher =
                new SmokeOutboxDispatcher(outbox, ignored -> PublishResult.CONFIRMED);

        assertThat(dispatcher.dispatchPending(10)).isEqualTo(1);

        assertThat(outbox.find(new EventId("event-1")))
                .get()
                .extracting(OutboxRecord::status)
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.findPending(10)).isEmpty();
    }

    @Test
    void failedAndExceptionalPublishesStayPendingUntilAConfirmedRetry() {
        InMemoryOutboxStore outbox = pendingOutbox(envelope("event-1", "smoke-1"));
        AtomicInteger attempts = new AtomicInteger();
        SmokeOutboxDispatcher dispatcher = new SmokeOutboxDispatcher(outbox, ignored -> switch (attempts.incrementAndGet()) {
            case 1 -> PublishResult.FAILED;
            case 2 -> throw new IllegalStateException("publisher unavailable");
            default -> PublishResult.CONFIRMED;
        });

        assertThat(dispatcher.dispatchPending(10)).isZero();
        assertThat(outbox.findPending(10)).hasSize(1);
        assertThat(dispatcher.dispatchPending(10)).isZero();
        assertThat(outbox.findPending(10)).hasSize(1);
        assertThat(dispatcher.dispatchPending(10)).isEqualTo(1);

        assertThat(attempts).hasValue(3);
        assertThat(outbox.find(new EventId("event-1")))
                .get()
                .extracting(OutboxRecord::status)
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void completedInboxEventIsDuplicateAndRunsHandlerOnlyOnce() {
        InMemoryInboxGuard guard = new InMemoryInboxGuard();
        SmokeInboxConsumer consumer = new SmokeInboxConsumer(guard);
        AtomicInteger handled = new AtomicInteger();
        MessageEnvelope envelope = envelope("event-1", "smoke-1");

        assertThat(consumer.consume(envelope, ignored -> handled.incrementAndGet()))
                .isEqualTo(SmokeInboxConsumer.ConsumptionResult.PROCESSED);
        assertThat(consumer.consume(envelope, ignored -> handled.incrementAndGet()))
                .isEqualTo(SmokeInboxConsumer.ConsumptionResult.DUPLICATE);

        assertThat(handled).hasValue(1);
    }

    @Test
    void failedInboxHandlingAbortsTheClaimAndCanBeRetried() {
        InMemoryInboxGuard guard = new InMemoryInboxGuard();
        SmokeInboxConsumer consumer = new SmokeInboxConsumer(guard);
        AtomicInteger attempts = new AtomicInteger();
        MessageEnvelope envelope = envelope("event-1", "smoke-1");

        assertThatThrownBy(() -> consumer.consume(envelope, ignored -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("handler failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("handler failed");
        assertThat(consumer.consume(envelope, ignored -> attempts.incrementAndGet()))
                .isEqualTo(SmokeInboxConsumer.ConsumptionResult.PROCESSED);
        assertThat(consumer.consume(envelope, ignored -> attempts.incrementAndGet()))
                .isEqualTo(SmokeInboxConsumer.ConsumptionResult.DUPLICATE);

        assertThat(attempts).hasValue(2);
    }

    @Test
    void staleInboxClaimCannotCompleteOrAbortANewerAttempt() {
        InMemoryInboxGuard guard = new InMemoryInboxGuard();
        EventId eventId = new EventId("event-1");
        InboxGuard.Acquired firstAttempt = acquired(guard.begin(eventId));

        assertThat(guard.abort(firstAttempt)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);
        InboxGuard.Acquired retry = acquired(guard.begin(eventId));

        assertThat(retry.claimToken()).isNotEqualTo(firstAttempt.claimToken());
        assertThat(guard.complete(firstAttempt)).isEqualTo(InboxGuard.ClaimResolution.STALE);
        assertThat(guard.abort(firstAttempt)).isEqualTo(InboxGuard.ClaimResolution.STALE);
        assertThat(guard.begin(eventId)).isEqualTo(new InboxGuard.InProgress(eventId));
        assertThat(guard.complete(retry)).isEqualTo(InboxGuard.ClaimResolution.APPLIED);
        assertThat(guard.begin(eventId)).isEqualTo(new InboxGuard.Duplicate(eventId));
    }

    private static InMemoryOutboxStore pendingOutbox(MessageEnvelope envelope) {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        outbox.save(OutboxRecord.pending(envelope));
        return outbox;
    }

    private static MessageEnvelope envelope(String eventId, String aggregateId) {
        return new MessageEnvelope(
                eventId,
                "SmokeOccurred",
                1,
                aggregateId,
                "Smoke",
                OCCURRED_AT,
                "request-1",
                Map.of("aggregateId", aggregateId));
    }

    private static InboxGuard.Acquired acquired(InboxGuard.BeginResult result) {
        assertThat(result).isInstanceOf(InboxGuard.Acquired.class);
        return (InboxGuard.Acquired) result;
    }
}
