package com.xzwl.cloud.canvas.domain;

import java.time.Instant;
import java.util.Objects;

/** Canvas aggregate root for the editor workspace. */
public final class Canvas {

  private final String id;
  private final String ownerId;
  private final String name;
  private final CanvasContent content;
  private final long version;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final boolean deleted;

  private Canvas(
      String id,
      String ownerId,
      String name,
      CanvasContent content,
      long version,
      Instant createdAt,
      Instant updatedAt,
      boolean deleted) {
    this.id = requireNonBlank(id, "id");
    this.ownerId = requireNonBlank(ownerId, "ownerId");
    this.name = requireNonBlank(name, "name");
    this.content = Objects.requireNonNull(content, "content");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    this.version = version;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.deleted = deleted;
  }

  public static Canvas create(String id, String ownerId, String name, Instant createdAt) {
    Instant timestamp = Objects.requireNonNull(createdAt, "createdAt");
    return new Canvas(id, ownerId, name, CanvasContent.empty(), 1, timestamp, timestamp, false);
  }

  /** Restores a persisted immutable snapshot without replaying business commands. */
  public static Canvas restore(String id, String ownerId, String name, CanvasContent content,
      long version, Instant createdAt, Instant updatedAt, boolean deleted) {
    return new Canvas(id, ownerId, name, content, version, createdAt, updatedAt, deleted);
  }

  public String id() {
    return id;
  }

  public String ownerId() {
    return ownerId;
  }

  public String name() {
    return name;
  }

  public CanvasContent content() {
    return content;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public boolean deleted() {
    return deleted;
  }

  /**
   * Returns a new immutable snapshot when the caller still owns the expected version.
   *
   * <p>The aggregate does not mutate in place, so a persistence adapter can use the returned
   * version as the compare-and-swap value when it stores the snapshot.
   */
  public Canvas save(CanvasContent newContent, long expectedVersion, Instant savedAt) {
    return save(newContent, expectedVersion, savedAt, name);
  }

  public Canvas save(CanvasContent newContent, long expectedVersion, Instant savedAt, String newName) {
    if (expectedVersion != version) {
      throw new IllegalStateException("expectedVersion does not match canvas version");
    }
    Instant timestamp = Objects.requireNonNull(savedAt, "savedAt");
    if (deleted) {
      throw new IllegalStateException("deleted canvas cannot be saved");
    }
    return new Canvas(id, ownerId, newName, newContent, version + 1, createdAt, timestamp, false);
  }

  public Canvas copy(String newId, String newName, Instant copiedAt) {
    if (deleted) {
      throw new IllegalStateException("deleted canvas cannot be copied");
    }
    Instant timestamp = Objects.requireNonNull(copiedAt, "copiedAt");
    return new Canvas(newId, ownerId, newName, content, 1, timestamp, timestamp, false);
  }

  public Canvas delete(long expectedVersion, Instant deletedAt) {
    if (expectedVersion != version) {
      throw new IllegalStateException("expectedVersion does not match canvas version");
    }
    if (deleted) {
      return this;
    }
    Instant timestamp = Objects.requireNonNull(deletedAt, "deletedAt");
    return new Canvas(id, ownerId, name, content, version + 1, createdAt, timestamp, true);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
