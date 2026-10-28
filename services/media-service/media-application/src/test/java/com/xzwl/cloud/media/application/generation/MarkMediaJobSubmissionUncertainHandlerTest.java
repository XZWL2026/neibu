package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class MarkMediaJobSubmissionUncertainHandlerTest {

  public static void main(String[] args) {
    marksPreparingImageAsSubmissionUncertain();
    replaysUncertainMarkIdempotently();
    ignoresLateUncertainMarkAfterFailure();
    System.out.println("MarkMediaJobSubmissionUncertainHandlerTest passed: 3");
  }

  private static void marksPreparingImageAsSubmissionUncertain() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant uncertainAt = Instant.parse("2026-09-04T09:00:10Z");
    MediaJob job = MediaJob.prepare("job-1", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    RecordingStore store = new RecordingStore(job);

    MediaJob marked =
        new MarkMediaJobSubmissionUncertainHandler(store)
            .handle(new MarkMediaJobSubmissionUncertainCommand("job-1", uncertainAt));

    assertEquals(MediaJobStatus.SUBMISSION_UNCERTAIN, marked.status(), "status");
    assertEquals(uncertainAt, marked.submissionUncertainAt(), "uncertain at");
    assertSame(job, store.saved, "saved aggregate");
  }

  private static void replaysUncertainMarkIdempotently() {
    MediaJob job =
        MediaJob.prepare(
            "job-2",
            MediaType.VIDEO,
            "seedance-2.0",
            "海边日落",
            Instant.parse("2026-09-04T09:00:00Z"));
    Instant uncertainAt = Instant.parse("2026-09-04T09:00:10Z");
    RecordingStore store = new RecordingStore(job);
    MarkMediaJobSubmissionUncertainHandler handler =
        new MarkMediaJobSubmissionUncertainHandler(store);

    handler.handle(new MarkMediaJobSubmissionUncertainCommand("job-2", uncertainAt));
    MediaJob replayed =
        handler.handle(
            new MarkMediaJobSubmissionUncertainCommand(
                "job-2", Instant.parse("2026-09-04T09:00:11Z")));

    assertEquals(MediaJobStatus.SUBMISSION_UNCERTAIN, replayed.status(), "replayed status");
    assertEquals(uncertainAt, replayed.submissionUncertainAt(), "original uncertain at");
    assertEquals(1, store.saveCount, "replayed save count");
  }

  private static void ignoresLateUncertainMarkAfterFailure() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:05Z");
    Instant uncertainAt = Instant.parse("2026-09-04T09:00:10Z");
    MediaJob job = MediaJob.prepare("job-failed", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    job.accept("provider-task", acceptedAt);
    job.fail("provider timed out", uncertainAt);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = new MarkMediaJobSubmissionUncertainHandler(store).handle(
        new MarkMediaJobSubmissionUncertainCommand("job-failed", uncertainAt));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late uncertain status");
    assertEquals("provider timed out", result.failureMessage(), "late uncertain failure");
    assertEquals(0, store.saveCount, "late uncertain save count");
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static void assertSame(Object expected, Object actual, String label) {
    if (expected != actual) {
      throw new AssertionError(label + " expected same instance");
    }
  }

  private static final class RecordingStore implements MediaJobSubmissionUncertainStore {

    private final MediaJob loaded;
    private MediaJob saved;
    private int saveCount;

    private RecordingStore(MediaJob loaded) {
      this.loaded = loaded;
    }

    @Override
    public MediaJob load(String jobId) {
      if (!loaded.id().equals(jobId)) {
        throw new AssertionError("unexpected job id: " + jobId);
      }
      return loaded;
    }

    @Override
    public void save(MediaJob job) {
      saved = job;
      saveCount++;
    }
  }
}
