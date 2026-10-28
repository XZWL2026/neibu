package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

/** Application input for persisting a media generation intent. */
public record SubmitMediaJobCommand(
    String jobId,
    MediaType mediaType,
    String requestedModel,
    String prompt,
    Instant createdAt) {}
