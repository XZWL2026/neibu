package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class FailMediaJobHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant FAILED_AT = Instant.parse("2026-09-04T07:00:02Z");

  public static void main(String[] args) {
    failsAcceptedProviderJob();
    failsPendingStorageJob();
    failsPendingEnhancementStorageJob();
    failsPendingEnhancementJob();
    repeatsFailureIdempotently();
    ignoresLateFailureAfterCompletion();
    ignoresConflictingFailureAfterFailure();
    System.out.println("FailMediaJobHandlerTest passed: 7");
  }

  private static void failsAcceptedProviderJob() {
    RecordingStore store = new RecordingStore(acceptedJob("job-failed"));
    MediaJob result = handler(store).handle(
        new FailMediaJobCommand("job-failed", "provider rejected request", FAILED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "failed status");
    assertEquals("provider rejected request", result.failureMessage(), "failure message");
    assertEquals(FAILED_AT, result.failedAt(), "failedAt");
    assertEquals(1, store.saveCount, "failed save count");
  }

  private static void failsPendingEnhancementJob() {
    MediaJob job = acceptedJob("job-enhance-failed");
    job.queueEnhancement("tos://720", FAILED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new FailMediaJobCommand("job-enhance-failed", "enhancement failed", FAILED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "enhancement failed status");
    assertEquals("enhancement failed", result.failureMessage(), "enhancement failure message");
  }

  private static void failsPendingStorageJob() {
    MediaJob job = acceptedJob("job-storage-failed");
    job.queueStorage("provider://storage-failed", FAILED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new FailMediaJobCommand("job-storage-failed", "storage failed", FAILED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "storage failed status");
    assertEquals("storage failed", result.failureMessage(), "storage failure message");
  }

  private static void failsPendingEnhancementStorageJob() {
    MediaJob job = acceptedJob("job-enhancement-storage-failed");
    job.queueEnhancement("tos://720", FAILED_AT);
    job.queueEnhancementOutputStorage("provider://1080", FAILED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new FailMediaJobCommand(
        "job-enhancement-storage-failed", "final storage failed", FAILED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "enhancement storage failed status");
    assertEquals("final storage failed", result.failureMessage(), "enhancement storage failure message");
  }

  private static void repeatsFailureIdempotently() {
    RecordingStore store = new RecordingStore(acceptedJob("job-repeat-failure"));
    FailMediaJobHandler handler = handler(store);
    FailMediaJobCommand command =
        new FailMediaJobCommand("job-repeat-failure", "provider timeout", FAILED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.FAILED, repeated.status(), "repeated failed status");
    assertEquals(1, store.saveCount, "repeated save count");
  }

  private static void ignoresLateFailureAfterCompletion() {
    MediaJob job = acceptedJob("job-completed");
    job.complete("tos://480", FAILED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new FailMediaJobCommand("job-completed", "late failure", FAILED_AT));

    assertEquals(MediaJobStatus.COMPLETED, result.status(), "completed late failure status");
    assertEquals(0, store.saveCount, "completed late failure save count");
  }

  private static void ignoresConflictingFailureAfterFailure() {
    MediaJob job = acceptedJob("job-conflicting-failure");
    job.fail("first failure", FAILED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new FailMediaJobCommand("job-conflicting-failure", "second failure", FAILED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "conflicting failure status");
    assertEquals("first failure", result.failureMessage(), "conflicting failure message");
    assertEquals(0, store.saveCount, "conflicting failure save count");
  }

  private static FailMediaJobHandler handler(RecordingStore store) {
    return new FailMediaJobHandler(store);
  }

  private static MediaJob acceptedJob(String jobId) {
    MediaJob job = MediaJob.prepare(jobId, MediaType.VIDEO, "seedance", "a prompt", CREATED_AT);
    job.accept("provider-" + jobId, ACCEPTED_AT);
    return job;
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static final class RecordingStore implements MediaJobFailureStore {

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
