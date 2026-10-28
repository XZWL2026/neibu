package com.xzwl.cloud.messaging;

@FunctionalInterface
public interface EventPublisher {

    /** Returns {@link PublishResult#CONFIRMED} only after the broker acknowledges this envelope. */
    PublishResult publish(MessageEnvelope envelope);
}
