package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;

public final class CompleteMediaStorageHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant STORAGE_QUEUED_AT = Instant.parse("2026-09-04T07:00:02Z");
  private static final Instant COMPLETED_AT = Instant.parse("2026-09-04T07:00:03Z");

  public static void main(String[] args) {
    completesStoragePendingImage();
    repeatsStorageCompletionIdempotently();
    rejectsJobThatIsNotStoragePending();
    ignoresLateStorageCompletionAfterFailure();
    System.out.println("CompleteMediaStorageHandlerTest passed: 4");
  }

  private static void completesStoragePendingImage() {
    RecordingStore store = new RecordingStore(storagePendingImage("storage-1"));
    MediaJob result = handler(store).handle(
        new CompleteMediaStorageCommand("storage-1", "tos://archived-image", COMPLETED_AT));

    assertEquals(MediaJobStatus.COMPLETED, result.status(), "storage status");
    assertEquals("tos://archived-image", result.outputUrl(), "archived output");
    assertEquals(COMPLETED_AT, result.completedAt(), "storage completedAt");
    assertEquals(1, store.saveCount, "storage save count");
  }

  private static void repeatsStorageCompletionIdempotently() {
    RecordingStore store = new RecordingStore(storagePendingImage("storage-repeat"));
    CompleteMediaStorageHandler handler = handler(store);
    CompleteMediaStorageCommand command = new CompleteMediaStorageCommand(
        "storage-repeat", "tos://archived-repeat", COMPLETED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.COMPLETED, repeated.status(), "repeated storage status");
    assertEquals(1, store.saveCount, "repeated storage save count");
  }

  private static void rejectsJobThatIsNotStoragePending() {
    RecordingStore store = new RecordingStore(acceptedJob("storage-not-ready"));
    try {
      handler(store).handle(new CompleteMediaStorageCommand(
          "storage-not-ready", "tos://archived", COMPLETED_AT));
      throw new AssertionError("non-storage-pending job should be rejected");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  private static void ignoresLateStorageCompletionAfterFailure() {
    MediaJob job = storagePendingImage("storage-failed");
    job.fail("storage timed out", COMPLETED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(new CompleteMediaStorageCommand(
        "storage-failed", "tos://late", COMPLETED_AT));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late storage status");
    assertEquals("storage timed out", result.failureMessage(), "late storage failure");
    assertEquals(0, store.saveCount, "late storage save count");
  }

  private static CompleteMediaStorageHandler handler(RecordingStore store) {
    return new CompleteMediaStorageHandler(store);
  }

  private static MediaJob storagePendingImage(String jobId) {
    MediaJob job = acceptedJob(jobId);
    job.queueStorage("provider://image", STORAGE_QUEUED_AT);
    return job;
  }

  private static MediaJob acceptedJob(String jobId) {
    MediaJob job = MediaJob.prepare(jobId, MediaType.IMAGE, "image-model", "a prompt", CREATED_AT);
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
