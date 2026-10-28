package com.xzwl.cloud.media.domain.generation;

/** Persisted workflow states for a media generation job. */
public enum MediaJobStatus {
  PREPARING,
  PROVIDER_ACCEPTED,
  SUBMISSION_UNCERTAIN,
  STORAGE_PENDING,
  ENHANCEMENT_PENDING,
  ENHANCEMENT_STORAGE_PENDING,
  COMPLETED,
  FAILED
}
