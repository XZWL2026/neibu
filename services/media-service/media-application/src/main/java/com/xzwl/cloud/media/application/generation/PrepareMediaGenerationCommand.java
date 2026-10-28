package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;

/** Application input for routing, pricing and balance preparation. */
public record PrepareMediaGenerationCommand(
    MediaGenerationIdentity identity,
    String requestedModel,
    String resolution,
    BigDecimal quantity,
    MediaChannelSelection selectedChannel) {}
