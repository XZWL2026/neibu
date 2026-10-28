package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;
import java.time.Instant;

public final class CompleteVideoEnhancementHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant QUEUED_AT = Instant.parse("2026-09-04T07:00:02Z");
  private static final Instant COMPLETED_AT = Instant.parse("2026-09-04T07:00:03Z");

  public static void main(String[] args) {
    queuesEnhancementOutputForStorage();
    repeatsFinalizationIdempotently();
    rejectsJobThatIsNotWaitingForEnhancement();
    ignoresLateEnhancementAfterFailure();
    System.out.println("CompleteVideoEnhancementHandlerTest passed: 4");
  }

  private static void queuesEnhancementOutputForStorage() {
    RecordingStore store = new RecordingStore(enhancementPendingJob("job-final"));
    MediaJob result = handler(store).handle(
        new CompleteVideoEnhancementCommand("job-final", "tos://1080", COMPLETED_AT));

    assertEquals(MediaJobStatus.ENHANCEMENT_STORAGE_PENDING, result.status(), "final status");
    assertEquals("tos://1080", result.enhancementOutputSourceUrl(), "final storage source");
    assertEquals(COMPLETED_AT, result.enhancementOutputQueuedAt(), "final storage queuedAt");
    assertEquals(1, store.saveCount, "final save count");
  }

  private static void repeatsFinalizationIdempotently() {
    RecordingStore store = new RecordingStore(enhancementPendingJob("job-repeat-final"));
    CompleteVideoEnhancementHandler handler = handler(store);
    CompleteVideoEnhancementCommand command =
        new CompleteVideoEnhancementCommand("job-repeat-final", "tos://1080", COMPLETED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.ENHANCEMENT_STORAGE_PENDING, repeated.status(), "repeated status");
    assertEquals(1, store.saveCount, "repeated save count");
  }

  private static void rejectsJobThatIsNotWaitingForEnhancement() {
    RecordingStore store = new RecordingStore(acceptedJob("job-not-ready"));
    try {
      handler(store).handle(
          new CompleteVideoEnhancementCommand("job-not-ready", "tos://1080", COMPLETED_AT));
      throw new AssertionError("non-pending job should be rejected");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void ignoresLateEnhancementAfterFailure() {
    MediaJob job = enhancementPendingJob("job-final-failed");
    job.fail("enhancement timed out", COMPLETED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        new CompleteVideoEnhancementCommand("job-final-failed", "tos://late", COMPLETED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late enhancement status");
    assertEquals("enhancement timed out", result.failureMessage(), "late enhancement failure");
    assertEquals(0, store.saveCount, "late enhancement save count");
  }

  private static CompleteVideoEnhancementHandler handler(RecordingStore store) {
    return new CompleteVideoEnhancementHandler(store);
  }

  private static MediaJob enhancementPendingJob(String jobId) {
    MediaJob job = acceptedJob(jobId);
    VideoDeliveryPlan plan = new PlanVideoDeliveryHandler().handle(
        new PlanVideoDeliveryCommand(VideoResolution.R1080P));
    if (plan.mode() != com.xzwl.cloud.media.domain.generation.VideoDeliveryMode.ENHANCEMENT) {
      throw new AssertionError("test plan must require enhancement");
    }
    job.queueEnhancement("tos://720", QUEUED_AT);
    return job;
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

  private static final class RecordingStore implements MediaJobEnhancementStore {

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
