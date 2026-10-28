package com.xzwl.cloud.kernel.id;

import java.time.Instant;
import java.util.Objects;

public final class SnowflakeIdGenerator {

    public static final long EPOCH_MILLIS =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final long MAX_TIMESTAMP_DELTA = (1L << 41) - 1;
    private static final long MAX_WORKER_ID = (1L << 10) - 1;
    private static final long MAX_SEQUENCE = (1L << 12) - 1;
    private static final int WORKER_SHIFT = 12;
    private static final int TIMESTAMP_SHIFT = 22;
    private static final int DEFAULT_MAX_WAIT_STEPS = 1_000_000;

    private final long workerId;
    private final SnowflakeClock clock;
    private final Runnable waitStep;
    private final int maxWaitSteps;

    private long lastTimestamp = -1;
    private long sequence;

    public SnowflakeIdGenerator(long workerId) {
        this(
                workerId,
                SnowflakeClock.system(),
                Thread::onSpinWait,
                DEFAULT_MAX_WAIT_STEPS);
    }

    SnowflakeIdGenerator(
            long workerId, SnowflakeClock clock, Runnable waitStep, int maxWaitSteps) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new SnowflakeIdException(SnowflakeIdException.Reason.INVALID_WORKER_ID);
        }
        if (maxWaitSteps < 1) {
            throw new IllegalArgumentException("maxWaitSteps must be positive");
        }
        this.workerId = workerId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waitStep = Objects.requireNonNull(waitStep, "waitStep");
        this.maxWaitSteps = maxWaitSteps;
    }

    public synchronized long nextId() {
        long timestamp = clock.currentTimeMillis();
        validateTimestamp(timestamp);
        if (lastTimestamp >= 0 && timestamp < lastTimestamp) {
            throw new SnowflakeIdException(SnowflakeIdException.Reason.CLOCK_ROLLBACK);
        }

        long nextSequence;
        if (timestamp == lastTimestamp) {
            if (sequence == MAX_SEQUENCE) {
                timestamp = awaitNextMillis(lastTimestamp);
                validateTimestamp(timestamp);
                nextSequence = 0;
            } else {
                nextSequence = sequence + 1;
            }
        } else {
            nextSequence = 0;
        }

        if (timestamp == EPOCH_MILLIS && workerId == 0 && nextSequence == 0) {
            timestamp = awaitNextMillis(timestamp);
            validateTimestamp(timestamp);
        }
        long delta = timestamp - EPOCH_MILLIS;
        long id =
                (delta << TIMESTAMP_SHIFT)
                        | (workerId << WORKER_SHIFT)
                        | nextSequence;
        if (id <= 0) {
            throw new SnowflakeIdException(SnowflakeIdException.Reason.TIMESTAMP_OVERFLOW);
        }

        lastTimestamp = timestamp;
        sequence = nextSequence;
        return id;
    }

    private long awaitNextMillis(long previousTimestamp) {
        for (int step = 0; step < maxWaitSteps; step++) {
            failIfInterrupted();
            waitStep.run();
            failIfInterrupted();

            long candidate = clock.currentTimeMillis();
            if (candidate < previousTimestamp) {
                throw new SnowflakeIdException(SnowflakeIdException.Reason.CLOCK_ROLLBACK);
            }
            if (candidate > previousTimestamp) {
                return candidate;
            }
        }
        throw new SnowflakeIdException(SnowflakeIdException.Reason.SEQUENCE_WAIT_TIMEOUT);
    }

    private static void failIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new SnowflakeIdException(
                    SnowflakeIdException.Reason.SEQUENCE_WAIT_INTERRUPTED);
        }
    }

    private static void validateTimestamp(long timestamp) {
        if (timestamp < EPOCH_MILLIS) {
            throw new SnowflakeIdException(SnowflakeIdException.Reason.CLOCK_BEFORE_EPOCH);
        }
        if (timestamp - EPOCH_MILLIS > MAX_TIMESTAMP_DELTA) {
            throw new SnowflakeIdException(SnowflakeIdException.Reason.TIMESTAMP_OVERFLOW);
        }
    }
}
