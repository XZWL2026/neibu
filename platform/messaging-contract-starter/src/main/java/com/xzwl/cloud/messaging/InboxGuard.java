package com.xzwl.cloud.messaging;

import java.util.Objects;

public interface InboxGuard {

    /**
     * Atomically claims {@code eventId}. Exactly one concurrent caller may receive {@link Acquired}; other callers
     * observe {@link InProgress}, while an already completed event yields {@link Duplicate}.
     */
    BeginResult begin(EventId eventId);

    /**
     * Atomically marks the matching current claim complete; a replaced or completed claim returns
     * {@link ClaimResolution#STALE}.
     */
    ClaimResolution complete(Acquired claim);

    /**
     * Atomically releases the matching current claim for retry; a replaced or completed claim returns
     * {@link ClaimResolution#STALE}.
     */
    ClaimResolution abort(Acquired claim);

    enum ClaimResolution {
        APPLIED,
        STALE
    }

    sealed interface BeginResult permits Acquired, InProgress, Duplicate {

        EventId eventId();
    }

    record Acquired(EventId eventId, String claimToken) implements BeginResult {

        public Acquired {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(claimToken, "claimToken must not be null");
            if (claimToken.isBlank()) {
                throw new IllegalArgumentException("claimToken must not be blank");
            }
        }
    }

    record InProgress(EventId eventId) implements BeginResult {

        public InProgress {
            Objects.requireNonNull(eventId, "eventId must not be null");
        }
    }

    record Duplicate(EventId eventId) implements BeginResult {

        public Duplicate {
            Objects.requireNonNull(eventId, "eventId must not be null");
        }
    }
}
