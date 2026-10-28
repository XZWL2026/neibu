package com.xzwl.cloud.smoke.event;

import com.xzwl.cloud.messaging.EventId;
import com.xzwl.cloud.messaging.InboxGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class InMemoryInboxGuard implements InboxGuard {

    private final Map<EventId, InboxEntry> entries = new HashMap<>();
    private long claimSequence;

    @Override
    public synchronized BeginResult begin(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        InboxEntry current = entries.get(eventId);
        if (current == null) {
            String claimToken = "claim-" + ++claimSequence;
            entries.put(eventId, new InboxEntry(InboxStatus.IN_PROGRESS, claimToken));
            return new Acquired(eventId, claimToken);
        }
        if (current.status() == InboxStatus.IN_PROGRESS) {
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
        entries.put(claim.eventId(), new InboxEntry(InboxStatus.COMPLETED, current.claimToken()));
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
                || current.status() != InboxStatus.IN_PROGRESS
                || !current.claimToken().equals(claim.claimToken())) {
            return null;
        }
        return current;
    }

    private enum InboxStatus {
        IN_PROGRESS,
        COMPLETED
    }

    private record InboxEntry(InboxStatus status, String claimToken) {}
}
