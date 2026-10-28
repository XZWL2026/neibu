package com.xzwl.cloud.storage.application.file;

import java.time.Instant;

public record CreateAccessUrlResult(String url, Instant expiresAt) {}
