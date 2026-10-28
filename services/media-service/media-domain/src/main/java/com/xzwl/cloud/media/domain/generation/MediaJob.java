package com.xzwl.cloud.media.domain.generation;

import java.time.Instant;
import java.util.Objects;

/** A persisted generation intent waiting for preparation by the media workflow. */
public final class MediaJob {

  private final String id;
  private final MediaType mediaType;
  private final String requestedModel;
  private final String prompt;
  private final Instant createdAt;
  private MediaJobStatus status;
  private String providerTaskId;
  private Instant acceptedAt;
  private Instant submissionUncertainAt;
  private String outputUrl;
  private Instant completedAt;
  private String storageSourceUrl;
  private Instant storageQueuedAt;
  private String enhancementSourceUrl;
  private Instant enhancementQueuedAt;
  private String enhancementOutputSourceUrl;
  private Instant enhancementOutputQueuedAt;
  private String failureMessage;
  private Instant failedAt;

  private MediaJob(
      String id,
      MediaType mediaType,
      String requestedModel,
      String prompt,
      Instant createdAt) {
    this.id = requireNonBlank(id, "id");
    this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    this.requestedModel = requireNonBlank(requestedModel, "requestedModel");
    this.prompt = requireNonBlank(prompt, "prompt");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = MediaJobStatus.PREPARING;
  }

  public static MediaJob prepare(
      String id,
      MediaType mediaType,
      String requestedModel,
      String prompt,
      Instant createdAt) {
    return new MediaJob(id, mediaType, requestedModel, prompt, createdAt);
  }

  public String id() {
    return id;
  }

  public MediaType mediaType() {
    return mediaType;
  }

  public String requestedModel() {
    return requestedModel;
  }

  public String prompt() {
    return prompt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public synchronized MediaJobStatus status() {
    return status;
  }

  public synchronized String providerTaskId() {
    return providerTaskId;
  }

  public synchronized Instant acceptedAt() {
    return acceptedAt;
  }

  public synchronized Instant submissionUncertainAt() {
    return submissionUncertainAt;
  }

  public synchronized String outputUrl() {
    return outputUrl;
  }

  public synchronized Instant completedAt() {
    return completedAt;
  }

  public synchronized String storageSourceUrl() {
    return storageSourceUrl;
  }

  public synchronized Instant storageQueuedAt() {
    return storageQueuedAt;
  }

  public synchronized String enhancementSourceUrl() {
    return enhancementSourceUrl;
  }

  public synchronized Instant enhancementQueuedAt() {
    return enhancementQueuedAt;
  }

  public synchronized String enhancementOutputSourceUrl() {
    return enhancementOutputSourceUrl;
  }

  public synchronized Instant enhancementOutputQueuedAt() {
    return enhancementOutputQueuedAt;
  }

  public synchronized String failureMessage() {
    return failureMessage;
  }

  public synchronized Instant failedAt() {
    return failedAt;
  }

  public synchronized boolean accept(String providerTaskId, Instant acceptedAt) {
    String validatedProviderTaskId = requireNonBlank(providerTaskId, "providerTaskId");
    Instant validatedAcceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
    if (validatedAcceptedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("acceptedAt must not be before createdAt");
    }
    if (status == MediaJobStatus.PROVIDER_ACCEPTED) {
      if (this.providerTaskId.equals(validatedProviderTaskId)) {
        return false;
      }
      throw new IllegalStateException("provider task cannot be replaced after acceptance");
    }
    if (status == MediaJobStatus.SUBMISSION_UNCERTAIN
        && validatedAcceptedAt.isBefore(submissionUncertainAt)) {
      throw new IllegalArgumentException("acceptedAt must not be before submissionUncertainAt");
    }
    if (status != MediaJobStatus.PREPARING
        && status != MediaJobStatus.SUBMISSION_UNCERTAIN) {
      throw new IllegalStateException("only a preparing or uncertain job can be accepted");
    }
    this.providerTaskId = validatedProviderTaskId;
    this.acceptedAt = validatedAcceptedAt;
    this.status = MediaJobStatus.PROVIDER_ACCEPTED;
    return true;
  }

  public synchronized boolean markSubmissionUncertain(Instant uncertainAt) {
    Instant validatedUncertainAt = Objects.requireNonNull(uncertainAt, "uncertainAt");
    if (validatedUncertainAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("uncertainAt must not be before createdAt");
    }
    if (status == MediaJobStatus.SUBMISSION_UNCERTAIN) {
      return false;
    }
    if (status != MediaJobStatus.PREPARING) {
      throw new IllegalStateException("only a preparing job can become submission uncertain");
    }
    submissionUncertainAt = validatedUncertainAt;
    status = MediaJobStatus.SUBMISSION_UNCERTAIN;
    return true;
  }

  public synchronized boolean complete(String outputUrl, Instant completedAt) {
    String validatedOutputUrl = requireNonBlank(outputUrl, "outputUrl");
    Instant validatedCompletedAt = requireAfterCreated(completedAt, "completedAt");
    if (status == MediaJobStatus.COMPLETED) {
      if (this.outputUrl.equals(validatedOutputUrl)
          && this.completedAt.equals(validatedCompletedAt)) {
        return false;
      }
      throw new IllegalStateException("completed output cannot be replaced");
    }
    if (status != MediaJobStatus.PROVIDER_ACCEPTED) {
      throw new IllegalStateException("only an accepted job can complete");
    }
    this.outputUrl = validatedOutputUrl;
    this.completedAt = validatedCompletedAt;
    this.status = MediaJobStatus.COMPLETED;
    return true;
  }

  public synchronized boolean queueStorage(String sourceUrl, Instant queuedAt) {
    String validatedSourceUrl = requireNonBlank(sourceUrl, "sourceUrl");
    Instant validatedQueuedAt = requireAfterCreated(queuedAt, "queuedAt");
    if (status == MediaJobStatus.STORAGE_PENDING) {
      if (this.storageSourceUrl.equals(validatedSourceUrl)
          && this.storageQueuedAt.equals(validatedQueuedAt)) {
        return false;
      }
      throw new IllegalStateException("storage source cannot be replaced");
    }
    if (status != MediaJobStatus.PROVIDER_ACCEPTED) {
      throw new IllegalStateException("only an accepted job can queue storage");
    }
    storageSourceUrl = validatedSourceUrl;
    storageQueuedAt = validatedQueuedAt;
    status = MediaJobStatus.STORAGE_PENDING;
    return true;
  }

  public synchronized boolean completeStorage(String outputUrl, Instant completedAt) {
    String validatedOutputUrl = requireNonBlank(outputUrl, "outputUrl");
    Instant validatedCompletedAt = requireAfterCreated(completedAt, "completedAt");
    if (status == MediaJobStatus.COMPLETED) {
      if (this.outputUrl.equals(validatedOutputUrl)
          && this.completedAt.equals(validatedCompletedAt)) {
        return false;
      }
      throw new IllegalStateException("completed output cannot be replaced");
    }
    if (status != MediaJobStatus.STORAGE_PENDING) {
      throw new IllegalStateException("only a storage-pending job can complete storage");
    }
    this.outputUrl = validatedOutputUrl;
    this.completedAt = validatedCompletedAt;
    this.status = MediaJobStatus.COMPLETED;
    return true;
  }

  public synchronized boolean completeEnhancement(String outputUrl, Instant completedAt) {
    String validatedOutputUrl = requireNonBlank(outputUrl, "outputUrl");
    Instant validatedCompletedAt = requireAfterCreated(completedAt, "completedAt");
    if (status == MediaJobStatus.COMPLETED) {
      if (this.outputUrl.equals(validatedOutputUrl)
          && this.completedAt.equals(validatedCompletedAt)) {
        return false;
      }
      throw new IllegalStateException("completed output cannot be replaced");
    }
    if (status != MediaJobStatus.ENHANCEMENT_PENDING) {
      throw new IllegalStateException("only an enhancement-pending job can complete enhancement");
    }
    this.outputUrl = validatedOutputUrl;
    this.completedAt = validatedCompletedAt;
    this.status = MediaJobStatus.COMPLETED;
    return true;
  }

  public synchronized boolean queueEnhancement(String sourceUrl, Instant queuedAt) {
    String validatedSourceUrl = requireNonBlank(sourceUrl, "sourceUrl");
    Instant validatedQueuedAt = requireAfterCreated(queuedAt, "queuedAt");
    if (status == MediaJobStatus.ENHANCEMENT_PENDING) {
      if (this.enhancementSourceUrl.equals(validatedSourceUrl)
          && this.enhancementQueuedAt.equals(validatedQueuedAt)) {
        return false;
      }
      throw new IllegalStateException("enhancement source cannot be replaced");
    }
    if (status != MediaJobStatus.PROVIDER_ACCEPTED) {
      throw new IllegalStateException("only an accepted job can queue enhancement");
    }
    this.enhancementSourceUrl = validatedSourceUrl;
    this.enhancementQueuedAt = validatedQueuedAt;
    this.status = MediaJobStatus.ENHANCEMENT_PENDING;
    return true;
  }

  public synchronized boolean queueEnhancementFromStorage(String sourceUrl, Instant queuedAt) {
    String validatedSourceUrl = requireNonBlank(sourceUrl, "sourceUrl");
    Instant validatedQueuedAt = requireAfterCreated(queuedAt, "queuedAt");
    if (status == MediaJobStatus.ENHANCEMENT_PENDING) {
      if (this.enhancementSourceUrl.equals(validatedSourceUrl)
          && this.enhancementQueuedAt.equals(validatedQueuedAt)) {
        return false;
      }
      throw new IllegalStateException("enhancement source cannot be replaced");
    }
    if (status == MediaJobStatus.COMPLETED
        && validatedSourceUrl.equals(this.enhancementSourceUrl)) {
      return false;
    }
    if (status != MediaJobStatus.STORAGE_PENDING) {
      throw new IllegalStateException("only a storage-pending job can queue enhancement");
    }
    this.enhancementSourceUrl = validatedSourceUrl;
    this.enhancementQueuedAt = validatedQueuedAt;
    this.status = MediaJobStatus.ENHANCEMENT_PENDING;
    return true;
  }

  public synchronized boolean queueEnhancementOutputStorage(String sourceUrl, Instant queuedAt) {
    String validatedSourceUrl = requireNonBlank(sourceUrl, "sourceUrl");
    Instant validatedQueuedAt = requireAfterCreated(queuedAt, "queuedAt");
    if (status == MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      if (this.enhancementOutputSourceUrl.equals(validatedSourceUrl)
          && this.enhancementOutputQueuedAt.equals(validatedQueuedAt)) {
        return false;
      }
      throw new IllegalStateException("enhancement output source cannot be replaced");
    }
    if (status == MediaJobStatus.COMPLETED
        && validatedSourceUrl.equals(this.enhancementOutputSourceUrl)) {
      return false;
    }
    if (status != MediaJobStatus.ENHANCEMENT_PENDING) {
      throw new IllegalStateException(
          "only an enhancement-pending job can queue enhancement output storage");
    }
    this.enhancementOutputSourceUrl = validatedSourceUrl;
    this.enhancementOutputQueuedAt = validatedQueuedAt;
    this.status = MediaJobStatus.ENHANCEMENT_STORAGE_PENDING;
    return true;
  }

  public synchronized boolean completeEnhancementStorage(String outputUrl, Instant completedAt) {
    String validatedOutputUrl = requireNonBlank(outputUrl, "outputUrl");
    Instant validatedCompletedAt = requireAfterCreated(completedAt, "completedAt");
    if (status == MediaJobStatus.COMPLETED) {
      if (this.outputUrl.equals(validatedOutputUrl)
          && this.completedAt.equals(validatedCompletedAt)) {
        return false;
      }
      throw new IllegalStateException("completed output cannot be replaced");
    }
    if (status != MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      throw new IllegalStateException(
          "only an enhancement-storage-pending job can complete storage");
    }
    this.outputUrl = validatedOutputUrl;
    this.completedAt = validatedCompletedAt;
    this.status = MediaJobStatus.COMPLETED;
    return true;
  }

  public synchronized boolean fail(String message, Instant failedAt) {
    String validatedMessage = requireNonBlank(message, "message");
    Instant validatedFailedAt = requireAfterCreated(failedAt, "failedAt");
    if (status == MediaJobStatus.FAILED) {
      if (this.failureMessage.equals(validatedMessage)
          && this.failedAt.equals(validatedFailedAt)) {
        return false;
      }
      throw new IllegalStateException("failure cannot be replaced");
    }
    if (status != MediaJobStatus.PROVIDER_ACCEPTED
        && status != MediaJobStatus.STORAGE_PENDING
        && status != MediaJobStatus.ENHANCEMENT_PENDING
        && status != MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      throw new IllegalStateException("only an active provider job can fail");
    }
    failureMessage = validatedMessage;
    this.failedAt = validatedFailedAt;
    status = MediaJobStatus.FAILED;
    return true;
  }

  private Instant requireAfterCreated(Instant value, String name) {
    Instant validated = Objects.requireNonNull(value, name);
    if (validated.isBefore(createdAt)) {
      throw new IllegalArgumentException(name + " must not be before createdAt");
    }
    return validated;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
