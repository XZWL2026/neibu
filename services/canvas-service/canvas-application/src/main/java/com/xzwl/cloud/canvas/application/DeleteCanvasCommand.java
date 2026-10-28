package com.xzwl.cloud.canvas.application;

import java.time.Instant;

/** Application input for a version-protected canvas deletion. */
public record DeleteCanvasCommand(String canvasId, long expectedVersion, Instant deletedAt) {}
