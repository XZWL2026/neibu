package com.xzwl.cloud.messaging;

import java.util.Objects;

/** An immutable outbox state value. Only {@link Pending} values can be newly created. */
public abstract sealed class OutboxRecord permits OutboxRecord.Pending, OutboxRecord.Published {

    private final MessageEnvelope envelope;
    private final OutboxStatus status;

    private OutboxRecord(MessageEnvelope envelope, OutboxStatus status) {
        this.envelope = Objects.requireNonNull(envelope, "envelope must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static Pending pending(MessageEnvelope envelope) {
        return new Pending(envelope);
    }

    public final MessageEnvelope envelope() {
        return envelope;
    }

    public final EventId eventId() {
        return envelope.deduplicationKey();
    }

    public final OutboxStatus status() {
        return status;
    }

    public abstract OutboxRecord recordPublishResult(PublishResult result);

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        OutboxRecord that = (OutboxRecord) other;
        return envelope.equals(that.envelope) && status == that.status;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(envelope, status);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[envelope=" + envelope + ", status=" + status + ']';
    }

    public static final class Pending extends OutboxRecord {

        private Pending(MessageEnvelope envelope) {
            super(envelope, OutboxStatus.PENDING);
        }

        @Override
        public OutboxRecord recordPublishResult(PublishResult result) {
            Objects.requireNonNull(result, "result must not be null");
            return result == PublishResult.CONFIRMED ? new Published(envelope()) : this;
        }
    }

    public static final class Published extends OutboxRecord {

        private Published(MessageEnvelope envelope) {
            super(envelope, OutboxStatus.PUBLISHED);
        }

        @Override
        public OutboxRecord recordPublishResult(PublishResult result) {
            Objects.requireNonNull(result, "result must not be null");
            throw new IllegalStateException("a published outbox record cannot transition again");
        }
    }
}
