package com.xzwl.cloud.kernel.id;

public final class SnowflakeIdException extends RuntimeException {

    public enum Reason {
        INVALID_WORKER_ID,
        CLOCK_BEFORE_EPOCH,
        CLOCK_ROLLBACK,
        TIMESTAMP_OVERFLOW,
        SEQUENCE_WAIT_INTERRUPTED,
        SEQUENCE_WAIT_TIMEOUT
    }

    private final Reason reason;

    public SnowflakeIdException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
