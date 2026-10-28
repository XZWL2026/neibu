package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class CompleteVideoEnhancementStorageHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant ENHANCEMENT_QUEUED_AT = Instant.parse("2026-09-04T07:00:02Z");
  private static final Instant STORAGE_QUEUED_AT = Instant.parse("2026-09-04T07:00:03Z");
  private static final Instant COMPLETED_AT = Instant.parse("2026-09-04T07:00:04Z");

  public static void main(String[] args) {
    completesArchivedEnhancementOutput();
    repeatsEnhancementStorageCompletionIdempotently();
    rejectsJobThatIsNotWaitingForEnhancementStorage();
    rejectsNonVideoJob();
    ignoresLateEnhancementStorageAfterFailure();
    System.out.println("CompleteVideoEnhancementStorageHandlerTest passed: 5");
  }

  private static void completesArchivedEnhancementOutput() {
    RecordingStore store = new RecordingStore(enhancementStoragePendingJob("final-storage-1"));
    MediaJob result = handler(store).handle(new CompleteVideoEnhancementStorageCommand(
        "final-storage-1", "tos://final-1080", COMPLETED_AT));

    assertEquals(MediaJobStatus.COMPLETED, result.status(), "final status");
    assertEquals("tos://final-1080", result.outputUrl(), "final output");
    assertEquals(COMPLETED_AT, result.completedAt(), "final completedAt");
    assertEquals(1, store.saveCount, "final save count");
  }

  private static void repeatsEnhancementStorageCompletionIdempotently() {
    RecordingStore store = new RecordingStore(enhancementStoragePendingJob("final-storage-repeat"));
    CompleteVideoEnhancementStorageHandler handler = handler(store);
    CompleteVideoEnhancementStorageCommand command = new CompleteVideoEnhancementStorageCommand(
        "final-storage-repeat", "tos://final-repeat", COMPLETED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.COMPLETED, repeated.status(), "repeated final status");
    assertEquals(1, store.saveCount, "repeated final save count");
  }

  private static void rejectsJobThatIsNotWaitingForEnhancementStorage() {
    RecordingStore store = new RecordingStore(enhancementPendingJob("final-storage-not-ready"));
    try {
      handler(store).handle(new CompleteVideoEnhancementStorageCommand(
          "final-storage-not-ready", "tos://final", COMPLETED_AT));
      throw new AssertionError("enhancement-pending job should not complete storage");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void rejectsNonVideoJob() {
    MediaJob job = MediaJob.prepare("image-final-storage", MediaType.IMAGE, "image-model", "a prompt", CREATED_AT);
    job.accept("provider-image-final-storage", ACCEPTED_AT);
    job.queueStorage("provider://image", STORAGE_QUEUED_AT);
    RecordingStore store = new RecordingStore(job);
    try {
      handler(store).handle(new CompleteVideoEnhancementStorageCommand(
          "image-final-storage", "tos://final", COMPLETED_AT));
      throw new AssertionError("image job should not complete video enhancement storage");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void ignoresLateEnhancementStorageAfterFailure() {
    MediaJob job = enhancementStoragePendingJob("final-storage-failed");
    job.fail("storage timed out", COMPLETED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new CompleteVideoEnhancementStorageCommand(
        "final-storage-failed", "tos://late", COMPLETED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late final status");
    assertEquals("storage timed out", result.failureMessage(), "late final failure");
    assertEquals(0, store.saveCount, "late final save count");
  }

  private static CompleteVideoEnhancementStorageHandler handler(RecordingStore store) {
    return new CompleteVideoEnhancementStorageHandler(store);
  }

  private static MediaJob enhancementStoragePendingJob(String jobId) {
    MediaJob job = enhancementPendingJob(jobId);
    job.queueEnhancementOutputStorage("provider://1080", STORAGE_QUEUED_AT);
    return job;
  }

  private static MediaJob enhancementPendingJob(String jobId) {
    MediaJob job = MediaJob.prepare(jobId, MediaType.VIDEO, "seedance", "a prompt", CREATED_AT);
    job.accept("provider-" + jobId, ACCEPTED_AT);
    job.queueEnhancement("tos://720", ENHANCEMENT_QUEUED_AT);
    return job;
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static final class RecordingStore implements MediaJobEnhancementStore, MediaJobStorageStore {

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
}
