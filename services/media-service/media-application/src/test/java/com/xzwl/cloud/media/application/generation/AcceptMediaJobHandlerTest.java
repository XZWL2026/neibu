package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class AcceptMediaJobHandlerTest {

  public static void main(String[] args) {
    marksPreparingJobAsProviderAccepted();
    replaysSameProviderTaskIdempotently();
    recoversUncertainSubmissionAsProviderAccepted();
    rejectsAcceptanceBeforeUncertainTime();
    ignoresLateAcceptanceAfterFailure();
    ignoresLateAcceptanceAfterCompletion();
    System.out.println("AcceptMediaJobHandlerTest passed: 6");
  }

  private static void marksPreparingJobAsProviderAccepted() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:05Z");
    MediaJob job = MediaJob.prepare("job-1", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    RecordingStore store = new RecordingStore(job);

    MediaJob accepted =
        new AcceptMediaJobHandler(store)
            .handle(new AcceptMediaJobCommand("job-1", "provider-task-1", acceptedAt));

    assertEquals(MediaJobStatus.PROVIDER_ACCEPTED, accepted.status(), "status");
    assertEquals("provider-task-1", accepted.providerTaskId(), "provider task");
    assertEquals(acceptedAt, accepted.acceptedAt(), "accepted at");
    assertSame(job, store.saved, "saved aggregate");
  }

  private static void replaysSameProviderTaskIdempotently() {
    MediaJob job =
        MediaJob.prepare(
            "job-2",
            MediaType.VIDEO,
            "seedance-2.0",
            "海边日落",
            Instant.parse("2026-09-04T09:00:00Z"));
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:05Z");
    RecordingStore store = new RecordingStore(job);
    AcceptMediaJobHandler handler = new AcceptMediaJobHandler(store);

    handler.handle(new AcceptMediaJobCommand("job-2", "provider-task-2", acceptedAt));
    MediaJob replayed =
        handler.handle(
            new AcceptMediaJobCommand(
                "job-2", "provider-task-2", Instant.parse("2026-09-04T09:00:06Z")));

    assertEquals(MediaJobStatus.PROVIDER_ACCEPTED, replayed.status(), "replayed status");
    assertEquals("provider-task-2", replayed.providerTaskId(), "replayed provider task");
    assertEquals(acceptedAt, replayed.acceptedAt(), "original accepted at");
    assertEquals(1, store.saveCount, "replayed save count");
  }

  private static void recoversUncertainSubmissionAsProviderAccepted() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant uncertainAt = Instant.parse("2026-09-04T09:00:10Z");
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:11Z");
    MediaJob job = MediaJob.prepare("job-uncertain", MediaType.VIDEO, "seedance-2.0", "海边日落", createdAt);
    job.markSubmissionUncertain(uncertainAt);
    RecordingStore store = new RecordingStore(job);

    MediaJob recovered = new AcceptMediaJobHandler(store)
        .handle(new AcceptMediaJobCommand("job-uncertain", "provider-task-recovered", acceptedAt));

    assertEquals(MediaJobStatus.PROVIDER_ACCEPTED, recovered.status(), "recovered status");
    assertEquals("provider-task-recovered", recovered.providerTaskId(), "recovered provider task");
    assertEquals(acceptedAt, recovered.acceptedAt(), "recovered accepted at");
  }

  private static void rejectsAcceptanceBeforeUncertainTime() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant uncertainAt = Instant.parse("2026-09-04T09:00:10Z");
    MediaJob job = MediaJob.prepare("job-uncertain-order", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    job.markSubmissionUncertain(uncertainAt);
    RecordingStore store = new RecordingStore(job);
    try {
      new AcceptMediaJobHandler(store)
          .handle(new AcceptMediaJobCommand(
              "job-uncertain-order", "provider-task-too-early", Instant.parse("2026-09-04T09:00:09Z")));
      throw new AssertionError("acceptance before uncertainty should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void ignoresLateAcceptanceAfterFailure() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:05Z");
    MediaJob job = MediaJob.prepare("job-failed", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    job.accept("provider-original", acceptedAt);
    job.fail("provider timed out", Instant.parse("2026-09-04T09:00:10Z"));
    RecordingStore store = new RecordingStore(job);

    MediaJob result = new AcceptMediaJobHandler(store).handle(
        new AcceptMediaJobCommand("job-failed", "provider-late", Instant.parse("2026-09-04T09:00:11Z")));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late acceptance failure status");
    assertEquals("provider-original", result.providerTaskId(), "late acceptance provider task");
    assertEquals(0, store.saveCount, "late acceptance failure save count");
  }

  private static void ignoresLateAcceptanceAfterCompletion() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    Instant acceptedAt = Instant.parse("2026-09-04T09:00:05Z");
    MediaJob job = MediaJob.prepare("job-completed", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt);
    job.accept("provider-original", acceptedAt);
    job.complete("tos://image", Instant.parse("2026-09-04T09:00:10Z"));
    RecordingStore store = new RecordingStore(job);

    MediaJob result = new AcceptMediaJobHandler(store).handle(
        new AcceptMediaJobCommand("job-completed", "provider-late", Instant.parse("2026-09-04T09:00:11Z")));

    assertEquals(MediaJobStatus.COMPLETED, result.status(), "late acceptance completed status");
    assertEquals("provider-original", result.providerTaskId(), "late acceptance completed task");
    assertEquals(0, store.saveCount, "late acceptance completed save count");
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

  private static final class RecordingStore implements MediaJobAcceptanceStore {

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
