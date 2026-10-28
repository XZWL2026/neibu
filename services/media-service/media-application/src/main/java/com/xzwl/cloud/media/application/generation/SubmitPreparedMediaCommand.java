package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import java.time.Instant;

/** Internal command joining a prepared cost, a persisted job and a selected provider channel. */
public record SubmitPreparedMediaCommand(
    String operationId,
    MediaJob job,
    MediaGenerationPreparationResult preparation,
    Instant acceptedAt) {}
