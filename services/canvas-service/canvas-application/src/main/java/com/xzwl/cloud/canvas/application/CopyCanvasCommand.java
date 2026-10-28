package com.xzwl.cloud.canvas.application;

import java.time.Instant;

/** Application input for copying a complete canvas snapshot. */
public record CopyCanvasCommand(String sourceCanvasId, String newCanvasId, String newName, Instant copiedAt) {}
