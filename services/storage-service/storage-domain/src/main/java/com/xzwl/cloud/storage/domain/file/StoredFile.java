package com.xzwl.cloud.storage.domain.file;

import java.time.Instant;
import java.util.Objects;

public record StoredFile(
    String fileId,
    String ownerId,
    String objectKey,
    String mimeType,
    long sizeBytes,
    String etag,
    FilePurpose purpose,
    Instant createdAt,
    Double durationSeconds,
    Integer width,
    Integer height,
    Double fps) {

  public StoredFile(String fileId, String ownerId, String objectKey, String mimeType, long sizeBytes,
      String etag, FilePurpose purpose, Instant createdAt) {
    this(fileId, ownerId, objectKey, mimeType, sizeBytes, etag, purpose, createdAt, null, null, null, null);
  }

  public StoredFile {
    fileId = requireNonBlank(fileId, "fileId");
    ownerId = requireNonBlank(ownerId, "ownerId");
    objectKey = requireNonBlank(objectKey, "objectKey");
    mimeType = requireNonBlank(mimeType, "mimeType");
    if (sizeBytes <= 0) throw new IllegalArgumentException("sizeBytes must be positive");
    etag = requireNonBlank(etag, "etag");
    purpose = Objects.requireNonNull(purpose, "purpose");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
