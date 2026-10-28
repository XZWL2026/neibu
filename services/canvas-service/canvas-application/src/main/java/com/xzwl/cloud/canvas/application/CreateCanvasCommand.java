package com.xzwl.cloud.canvas.application;

import java.time.Instant;

/** Application input for creating a canvas. */
public record CreateCanvasCommand(String canvasId, String ownerId, String name, Instant createdAt) {}
