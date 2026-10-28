package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;
import java.time.Instant;

public final class ApplyMediaProviderPollHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant OBSERVED_AT = Instant.parse("2026-09-04T07:00:02Z");
  private static final Instant LATER_OBSERVED_AT = Instant.parse("2026-09-04T07:00:03Z");

  public static void main(String[] args) {
    leavesPendingJobUnchanged();
    completesImageOutput();
    queuesNativeVideoOutputForStorage();
    queuesEnhancedVideoSourceForStorage();
    failsProviderJob();
    rejectsVideoCompletionWithoutPlan();
    replaysImageCompletionWithoutReplacingTimestamp();
    replaysEnhancementSourceStorageWithoutReplacingTimestamp();
    replaysFailureWithoutReplacingTimestamp();
    ignoresLateCompletionAfterFailure();
    ignoresLateFailureAfterCompletion();
    ignoresConflictingFailureAfterFailure();
    System.out.println("ApplyMediaProviderPollHandlerTest passed: 12");
  }

  private static void leavesPendingJobUnchanged() {
    RecordingStore store = new RecordingStore(acceptedJob("pending", MediaType.IMAGE));
    MediaJob result = handler(store).handle(
        new ApplyMediaProviderPollCommand(
            "pending", MediaProviderPollResult.pending(), null, OBSERVED_AT));

    assertEquals(MediaJobStatus.PROVIDER_ACCEPTED, result.status(), "pending status");
    assertEquals(0, store.saveCount, "pending save count");
  }

  private static void completesImageOutput() {
    RecordingStore store = new RecordingStore(acceptedJob("image", MediaType.IMAGE));
    MediaJob result = handler(store).handle(
        new ApplyMediaProviderPollCommand(
            "image", MediaProviderPollResult.completed("tos://image"), null, OBSERVED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "image status");
    assertEquals("tos://image", result.storageSourceUrl(), "image storage source");
    assertEquals(1, store.saveCount, "image save count");
  }

  private static void queuesEnhancedVideoSourceForStorage() {
    RecordingStore store = new RecordingStore(acceptedJob("video", MediaType.VIDEO));
    MediaJob result = handler(store).handle(
        new ApplyMediaProviderPollCommand(
            "video",
            MediaProviderPollResult.completed("tos://480"),
            VideoDeliveryPlan.forTarget(VideoResolution.R1080P),
            OBSERVED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "video status");
    assertEquals("tos://480", result.storageSourceUrl(), "video storage source");
    assertEquals(1, store.saveCount, "video save count");
  }

  private static void queuesNativeVideoOutputForStorage() {
    RecordingStore store = new RecordingStore(acceptedJob("video-native", MediaType.VIDEO));
    MediaJob result = handler(store).handle(
        new ApplyMediaProviderPollCommand(
            "video-native",
            MediaProviderPollResult.completed("provider://480"),
            VideoDeliveryPlan.forTarget(VideoResolution.R480P),
            OBSERVED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "native video status");
    assertEquals("provider://480", result.storageSourceUrl(), "native video storage source");
    assertEquals(OBSERVED_AT, result.storageQueuedAt(), "native video storage queuedAt");
    assertEquals(1, store.saveCount, "native video save count");
  }

  private static void failsProviderJob() {
    RecordingStore store = new RecordingStore(acceptedJob("failed", MediaType.VIDEO));
    MediaJob result = handler(store).handle(
        new ApplyMediaProviderPollCommand(
            "failed", MediaProviderPollResult.failed("provider rejected"), null, OBSERVED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "failed status");
    assertEquals("provider rejected", result.failureMessage(), "failure message");
    assertEquals(1, store.saveCount, "failed save count");
  }

  private static void rejectsVideoCompletionWithoutPlan() {
    RecordingStore store = new RecordingStore(acceptedJob("missing-plan", MediaType.VIDEO));
    try {
      handler(store).handle(
          new ApplyMediaProviderPollCommand(
              "missing-plan",
              MediaProviderPollResult.completed("tos://video"),
              null,
              OBSERVED_AT));
      throw new AssertionError("video completion without plan should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
    assertEquals(0, store.saveCount, "missing-plan save count");
  }

  private static void replaysImageCompletionWithoutReplacingTimestamp() {
    RecordingStore store = new RecordingStore(acceptedJob("image-replay", MediaType.IMAGE));
    ApplyMediaProviderPollHandler handler = handler(store);
    handler.handle(new ApplyMediaProviderPollCommand(
        "image-replay", MediaProviderPollResult.completed("tos://image"), null, OBSERVED_AT));

    MediaJob repeated = handler.handle(new ApplyMediaProviderPollCommand(
        "image-replay", MediaProviderPollResult.completed("tos://image"), null,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, repeated.status(), "image replay status");
    assertEquals(OBSERVED_AT, repeated.storageQueuedAt(), "image replay queuedAt");
    assertEquals(1, store.saveCount, "image replay save count");
  }

  private static void replaysEnhancementSourceStorageWithoutReplacingTimestamp() {
    RecordingStore store = new RecordingStore(acceptedJob("video-replay", MediaType.VIDEO));
    ApplyMediaProviderPollHandler handler = handler(store);
    VideoDeliveryPlan plan = VideoDeliveryPlan.forTarget(VideoResolution.R1080P);
    handler.handle(new ApplyMediaProviderPollCommand(
        "video-replay", MediaProviderPollResult.completed("tos://480"), plan, OBSERVED_AT));

    MediaJob repeated = handler.handle(new ApplyMediaProviderPollCommand(
        "video-replay", MediaProviderPollResult.completed("tos://480"), plan,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.STORAGE_PENDING, repeated.status(), "video replay status");
    assertEquals(OBSERVED_AT, repeated.storageQueuedAt(), "video replay queuedAt");
    assertEquals(1, store.saveCount, "video replay save count");
  }

  private static void replaysFailureWithoutReplacingTimestamp() {
    RecordingStore store = new RecordingStore(acceptedJob("failure-replay", MediaType.VIDEO));
    ApplyMediaProviderPollHandler handler = handler(store);
    handler.handle(new ApplyMediaProviderPollCommand(
        "failure-replay", MediaProviderPollResult.failed("provider rejected"), null, OBSERVED_AT));

    MediaJob repeated = handler.handle(new ApplyMediaProviderPollCommand(
        "failure-replay", MediaProviderPollResult.failed("provider rejected"), null,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.FAILED, repeated.status(), "failure replay status");
    assertEquals(OBSERVED_AT, repeated.failedAt(), "failure replay failedAt");
    assertEquals(1, store.saveCount, "failure replay save count");
  }

  private static void ignoresLateCompletionAfterFailure() {
    RecordingStore store = new RecordingStore(acceptedJob("late-completion", MediaType.IMAGE));
    ApplyMediaProviderPollHandler handler = handler(store);
    handler.handle(new ApplyMediaProviderPollCommand(
        "late-completion", MediaProviderPollResult.failed("provider rejected"), null,
        OBSERVED_AT));

    MediaJob repeated = handler.handle(new ApplyMediaProviderPollCommand(
        "late-completion", MediaProviderPollResult.completed("tos://late"), null,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.FAILED, repeated.status(), "late completion status");
    assertEquals("provider rejected", repeated.failureMessage(), "late completion failure");
    assertEquals(1, store.saveCount, "late completion save count");
  }

  private static void ignoresLateFailureAfterCompletion() {
    MediaJob job = acceptedJob("late-failure", MediaType.IMAGE);
    job.complete("tos://image", OBSERVED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob repeated = handler(store).handle(new ApplyMediaProviderPollCommand(
        "late-failure", MediaProviderPollResult.failed("late provider failure"), null,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.COMPLETED, repeated.status(), "late failure status");
    assertEquals("tos://image", repeated.outputUrl(), "late failure output");
    assertEquals(0, store.saveCount, "late failure save count");
  }

  private static void ignoresConflictingFailureAfterFailure() {
    RecordingStore store = new RecordingStore(acceptedJob("conflicting-failure", MediaType.IMAGE));
    ApplyMediaProviderPollHandler handler = handler(store);
    handler.handle(new ApplyMediaProviderPollCommand(
        "conflicting-failure", MediaProviderPollResult.failed("first failure"), null,
        OBSERVED_AT));

    MediaJob repeated = handler.handle(new ApplyMediaProviderPollCommand(
        "conflicting-failure", MediaProviderPollResult.failed("second failure"), null,
        LATER_OBSERVED_AT));

    assertEquals(MediaJobStatus.FAILED, repeated.status(), "conflicting failure status");
    assertEquals("first failure", repeated.failureMessage(), "conflicting failure message");
    assertEquals(1, store.saveCount, "conflicting failure save count");
  }

  private static ApplyMediaProviderPollHandler handler(RecordingStore store) {
    return new ApplyMediaProviderPollHandler(store);
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

  private static final class RecordingStore implements MediaJobPollStore {

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
