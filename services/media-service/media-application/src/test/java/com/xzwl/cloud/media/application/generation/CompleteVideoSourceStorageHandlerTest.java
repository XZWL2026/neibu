package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class CompleteVideoSourceStorageHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant STORAGE_QUEUED_AT = Instant.parse("2026-09-04T07:00:02Z");
  private static final Instant ENHANCEMENT_QUEUED_AT = Instant.parse("2026-09-04T07:00:03Z");

  public static void main(String[] args) {
    queuesEnhancementAfterVideoSourceArchive();
    repeatsSourceArchiveIdempotently();
    rejectsJobThatIsNotStoragePending();
    rejectsNonVideoJob();
    ignoresLateSourceArchiveAfterFailure();
    System.out.println("CompleteVideoSourceStorageHandlerTest passed: 5");
  }

  private static void queuesEnhancementAfterVideoSourceArchive() {
    RecordingStore store = new RecordingStore(storagePendingVideo("video-source-1"));
    MediaJob result = handler(store).handle(new CompleteVideoSourceStorageCommand(
        "video-source-1", "tos://archived-480", ENHANCEMENT_QUEUED_AT));

    assertEquals(MediaJobStatus.ENHANCEMENT_PENDING, result.status(), "enhancement status");
    assertEquals("tos://archived-480", result.enhancementSourceUrl(), "enhancement source");
    assertEquals(ENHANCEMENT_QUEUED_AT, result.enhancementQueuedAt(), "enhancement queuedAt");
    assertEquals(1, store.saveCount, "enhancement save count");
  }

  private static void repeatsSourceArchiveIdempotently() {
    RecordingStore store = new RecordingStore(storagePendingVideo("video-source-repeat"));
    CompleteVideoSourceStorageHandler handler = handler(store);
    CompleteVideoSourceStorageCommand command = new CompleteVideoSourceStorageCommand(
        "video-source-repeat", "tos://archived-repeat", ENHANCEMENT_QUEUED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.ENHANCEMENT_PENDING, repeated.status(), "repeated status");
    assertEquals(1, store.saveCount, "repeated save count");
  }

  private static void rejectsJobThatIsNotStoragePending() {
    RecordingStore store = new RecordingStore(acceptedJob("video-source-not-ready", MediaType.VIDEO));
    try {
      handler(store).handle(new CompleteVideoSourceStorageCommand(
          "video-source-not-ready", "tos://archived", ENHANCEMENT_QUEUED_AT));
      throw new AssertionError("non-storage-pending job should be rejected");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void rejectsNonVideoJob() {
    RecordingStore store = new RecordingStore(storagePendingImage("image-source"));
    try {
      handler(store).handle(new CompleteVideoSourceStorageCommand(
          "image-source", "tos://archived", ENHANCEMENT_QUEUED_AT));
      throw new AssertionError("image storage should not queue video enhancement");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void ignoresLateSourceArchiveAfterFailure() {
    MediaJob job = storagePendingVideo("video-source-failed");
    job.fail("storage timed out", ENHANCEMENT_QUEUED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new CompleteVideoSourceStorageCommand(
        "video-source-failed", "tos://late", ENHANCEMENT_QUEUED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late source status");
    assertEquals("storage timed out", result.failureMessage(), "late source failure");
    assertEquals(0, store.saveCount, "late source save count");
  }

  private static CompleteVideoSourceStorageHandler handler(RecordingStore store) {
    return new CompleteVideoSourceStorageHandler(store);
  }

  private static MediaJob storagePendingVideo(String jobId) {
    MediaJob job = acceptedJob(jobId, MediaType.VIDEO);
    job.queueStorage("provider://480", STORAGE_QUEUED_AT);
    return job;
  }

  private static MediaJob storagePendingImage(String jobId) {
    MediaJob job = acceptedJob(jobId, MediaType.IMAGE);
    job.queueStorage("provider://image", STORAGE_QUEUED_AT);
    return job;
  }

  private static MediaJob acceptedJob(String jobId, MediaType mediaType) {
    MediaJob job = MediaJob.prepare(jobId, mediaType, "seedance", "a prompt", CREATED_AT);
    job.accept("provider-" + jobId, ACCEPTED_AT);
    return job;
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static final class RecordingStore implements MediaJobStorageStore {

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
