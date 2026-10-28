package com.xzwl.cloud.media.application.generation;

import java.time.Instant;

/** Application input for preserving an ambiguous provider submission outcome. */
public record MarkMediaJobSubmissionUncertainCommand(String jobId, Instant uncertainAt) {}
