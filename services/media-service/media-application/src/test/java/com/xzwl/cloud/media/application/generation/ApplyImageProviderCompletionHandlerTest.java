package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class ApplyImageProviderCompletionHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant COMPLETED_AT = Instant.parse("2026-09-04T07:00:02Z");

  public static void main(String[] args) {
    completesAcceptedImageJob();
    repeatsImageCompletionIdempotently();
    replaysProviderCompletionAfterStorageArchive();
    rejectsVideoJob();
    ignoresLateCompletionAfterFailure();
    System.out.println("ApplyImageProviderCompletionHandlerTest passed: 5");
  }

  private static void completesAcceptedImageJob() {
    RecordingStore store = new RecordingStore(acceptedJob("image-1", MediaType.IMAGE));
    MediaJob result = handler(store).handle(
        new ApplyImageProviderCompletionCommand("image-1", "tos://image-1", COMPLETED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "image status");
    assertEquals("tos://image-1", result.storageSourceUrl(), "image storage source");
    assertEquals(COMPLETED_AT, result.storageQueuedAt(), "image storage queuedAt");
    assertEquals(1, store.saveCount, "image save count");
  }

  private static void repeatsImageCompletionIdempotently() {
    RecordingStore store = new RecordingStore(acceptedJob("image-repeat", MediaType.IMAGE));
    ApplyImageProviderCompletionHandler handler = handler(store);
    ApplyImageProviderCompletionCommand command =
        new ApplyImageProviderCompletionCommand("image-repeat", "tos://image-repeat", COMPLETED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.STORAGE_PENDING, repeated.status(), "repeated image status");
    assertEquals(1, store.saveCount, "repeated image save count");
  }

  private static void replaysProviderCompletionAfterStorageArchive() {
    RecordingStore store = new RecordingStore(acceptedJob("image-archived", MediaType.IMAGE));
    ApplyImageProviderCompletionHandler handler = handler(store);
    ApplyImageProviderCompletionCommand command =
        new ApplyImageProviderCompletionCommand("image-archived", "provider://image", COMPLETED_AT);

    handler.handle(command);
    new CompleteMediaStorageHandler(new StorageStoreAdapter(store)).handle(
        new CompleteMediaStorageCommand("image-archived", "tos://image-archived", COMPLETED_AT));

    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.COMPLETED, repeated.status(), "archived replay status");
    assertEquals("tos://image-archived", repeated.outputUrl(), "archived replay output");
    assertEquals(2, store.saveCount, "archived replay save count");
  }

  private static void rejectsVideoJob() {
    RecordingStore store = new RecordingStore(acceptedJob("video-1", MediaType.VIDEO));
    try {
      handler(store).handle(
          new ApplyImageProviderCompletionCommand("video-1", "tos://video-1", COMPLETED_AT));
      throw new AssertionError("video job should reject image completion");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void ignoresLateCompletionAfterFailure() {
    MediaJob job = acceptedJob("image-failed", MediaType.IMAGE);
    job.fail("provider timed out", COMPLETED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new ApplyImageProviderCompletionCommand("image-failed", "tos://late", COMPLETED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late image status");
    assertEquals("provider timed out", result.failureMessage(), "late image failure");
    assertEquals(0, store.saveCount, "late image save count");
  }

  private static ApplyImageProviderCompletionHandler handler(RecordingStore store) {
    return new ApplyImageProviderCompletionHandler(store);
  }

  private static MediaJob acceptedJob(String jobId, MediaType mediaType) {
    MediaJob job = MediaJob.prepare(jobId, mediaType, "image-model", "a prompt", CREATED_AT);
    job.accept("provider-" + jobId, ACCEPTED_AT);
    return job;
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static final class RecordingStore implements MediaJobCompletionStore {

    private MediaJob job;
    private int saveCount;

    private RecordingStore(MediaJob job) {
      this.job = job;
    }

    @Override
    public MediaJob load(String jobId) {
      if (!job.id().equals(jobId)) {
        throw new AssertionError("unexpected job id " + jobId);
      }
      return job;
    }

    @Override
    public void save(MediaJob job) {
      this.job = job;
      saveCount++;
    }
  }

  private static final class StorageStoreAdapter implements MediaJobStorageStore {

    private final RecordingStore delegate;

    private StorageStoreAdapter(RecordingStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public MediaJob load(String jobId) {
      return delegate.load(jobId);
    }

    @Override
    public void save(MediaJob job) {
      delegate.save(job);
    }
  }
}
