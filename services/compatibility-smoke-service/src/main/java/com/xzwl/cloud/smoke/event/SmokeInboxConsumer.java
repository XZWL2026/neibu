package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.InboxGuard;
import com.xzwl.cloud.messaging.MessageEnvelope;
import java.util.Objects;
import java.util.function.Consumer;

public final class SmokeInboxConsumer {

    private final InboxGuard inboxGuard;

    public SmokeInboxConsumer(InboxGuard inboxGuard) {
        this.inboxGuard = Objects.requireNonNull(inboxGuard, "inboxGuard must not be null");
    }

    public ConsumptionResult consume(MessageEnvelope envelope, Consumer<MessageEnvelope> handler) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        InboxGuard.BeginResult result = inboxGuard.begin(envelope.deduplicationKey());
        if (result instanceof InboxGuard.Duplicate) {
            return ConsumptionResult.DUPLICATE;
        }
        if (result instanceof InboxGuard.InProgress) {
            return ConsumptionResult.IN_PROGRESS;
        }
        InboxGuard.Acquired claim = (InboxGuard.Acquired) result;
        try {
            handler.accept(envelope);
        } catch (RuntimeException | Error failure) {
            inboxGuard.abort(claim);
            throw failure;
        }
        if (inboxGuard.complete(claim) != InboxGuard.ClaimResolution.APPLIED) {
            throw new IllegalStateException("inbox claim became stale before completion");
        }
        return ConsumptionResult.PROCESSED;
    }

    public enum ConsumptionResult {
        PROCESSED,
        DUPLICATE,
        IN_PROGRESS
    }
}
