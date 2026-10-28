package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.MediaResultReference;
import java.time.Instant;

/** Application input for adding a reference to an existing media result. */
public record AddMediaReferenceCommand(
    String canvasId,
    long expectedVersion,
    String nodeId,
    MediaResultReference media,
    Instant savedAt) {}
