package com.xzwl.cloud.media.application.worker;

import com.xzwl.cloud.media.application.generation.MediaJobPollStore;
import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class ExpireMediaJobHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant STARTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant BEFORE_DEADLINE = Instant.parse("2026-09-04T07:59:59Z");
  private static final Instant IMAGE_DEADLINE = Instant.parse("2026-09-04T08:00:01Z");
  private static final Instant VIDEO_DEADLINE = Instant.parse("2026-09-04T13:00:01Z");

  public static void main(String[] args) {
    expiresAcceptedImageAtDeadline();
    expiresStoragePendingImageAtDeadline();
    leavesActiveImageBeforeDeadline();
    expiresEnhancementPendingVideoAtDeadline();
    expiresEnhancementStoragePendingVideoAtDeadline();
    doesNotOverrideCompletedJob();
    rejectsSyncVideoMode();
    System.out.println("ExpireMediaJobHandlerTest passed: 7");
  }

  private static void expiresAcceptedImageAtDeadline() {
    RecordingStore store = new RecordingStore(acceptedJob("image-timeout", MediaType.IMAGE));
    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "image-timeout", MediaExecutionMode.ASYNC, STARTED_AT, IMAGE_DEADLINE));

    assertEquals(MediaJobStatus.FAILED, result.status(), "image timeout status");
    assertEquals("media provider timed out", result.failureMessage(), "image timeout message");
    assertEquals(IMAGE_DEADLINE, result.failedAt(), "image timeout time");
    assertEquals(1, store.saveCount, "image timeout save count");
  }

  private static void leavesActiveImageBeforeDeadline() {
    RecordingStore store = new RecordingStore(acceptedJob("image-wait", MediaType.IMAGE));
    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "image-wait", MediaExecutionMode.ASYNC, STARTED_AT, BEFORE_DEADLINE));

    assertEquals(MediaJobStatus.PROVIDER_ACCEPTED, result.status(), "image wait status");
    assertEquals(0, store.saveCount, "image wait save count");
  }

  private static void expiresStoragePendingImageAtDeadline() {
    MediaJob job = acceptedJob("image-storage-timeout", MediaType.IMAGE);
    job.queueStorage("provider://image-storage-timeout", STARTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "image-storage-timeout", MediaExecutionMode.ASYNC, STARTED_AT, IMAGE_DEADLINE));

    assertEquals(MediaJobStatus.FAILED, result.status(), "storage timeout status");
    assertEquals(1, store.saveCount, "storage timeout save count");
  }

  private static void expiresEnhancementPendingVideoAtDeadline() {
    MediaJob job = acceptedJob("video-timeout", MediaType.VIDEO);
    job.queueEnhancement("tos://480", STARTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "video-timeout", MediaExecutionMode.ASYNC, STARTED_AT, VIDEO_DEADLINE));

    assertEquals(MediaJobStatus.FAILED, result.status(), "video timeout status");
    assertEquals(1, store.saveCount, "video timeout save count");
  }

  private static void expiresEnhancementStoragePendingVideoAtDeadline() {
    MediaJob job = acceptedJob("video-enhancement-storage-timeout", MediaType.VIDEO);
    job.queueEnhancement("tos://720", STARTED_AT);
    job.queueEnhancementOutputStorage("provider://1080", STARTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "video-enhancement-storage-timeout", MediaExecutionMode.ASYNC, STARTED_AT, VIDEO_DEADLINE));

    assertEquals(MediaJobStatus.FAILED, result.status(), "enhancement storage timeout status");
    assertEquals(1, store.saveCount, "enhancement storage timeout save count");
  }

  private static void doesNotOverrideCompletedJob() {
    MediaJob job = acceptedJob("completed", MediaType.IMAGE);
    job.complete("tos://image", STARTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new ExpireMediaJobCommand(
        "completed", MediaExecutionMode.ASYNC, STARTED_AT, IMAGE_DEADLINE));

    assertEquals(MediaJobStatus.COMPLETED, result.status(), "completed status");
    assertEquals(0, store.saveCount, "completed save count");
  }

  private static void rejectsSyncVideoMode() {
    RecordingStore store = new RecordingStore(acceptedJob("video-sync", MediaType.VIDEO));
    try {
      handler(store).handle(new ExpireMediaJobCommand(
          "video-sync", MediaExecutionMode.SYNC, STARTED_AT, VIDEO_DEADLINE));
      throw new AssertionError("sync video timeout should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static ExpireMediaJobHandler handler(RecordingStore store) {
    return new ExpireMediaJobHandler(store, new MediaExecutionDeadlinePolicy());
  }

  private static MediaJob acceptedJob(String jobId, MediaType type) {
    MediaJob job = MediaJob.prepare(jobId, type, "seedance", "a prompt", CREATED_AT);
    job.accept("provider-" + jobId, STARTED_AT);
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
