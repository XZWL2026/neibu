package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SubmitMediaJobHandlerTest {

  public static void main(String[] args) {
    submitsImageIntentAndSavesPreparingJob();
    submitsVideoIntentAndSavesPreparingJob();
    System.out.println("SubmitMediaJobHandlerTest passed: 2");
  }

  private static void submitsImageIntentAndSavesPreparingJob() {
    Instant createdAt = Instant.parse("2026-09-04T09:00:00Z");
    RecordingStore store = new RecordingStore();

    MediaJob job =
        new SubmitMediaJobHandler(store)
            .handle(
                new SubmitMediaJobCommand(
                    "job-image-1", MediaType.IMAGE, "seedream-4.0", "一只猫", createdAt));

    assertEquals(MediaJobStatus.PREPARING, job.status(), "image status");
    assertEquals(MediaType.IMAGE, job.mediaType(), "image type");
    assertEquals("job-image-1", job.id(), "image id");
    assertEquals("seedream-4.0", job.requestedModel(), "image model");
    assertEquals("一只猫", job.prompt(), "image prompt");
    assertEquals(createdAt, job.createdAt(), "image created at");
    assertSame(job, store.saved.get(0), "image saved aggregate");
  }

  private static void submitsVideoIntentAndSavesPreparingJob() {
    RecordingStore store = new RecordingStore();

    MediaJob job =
        new SubmitMediaJobHandler(store)
            .handle(
                new SubmitMediaJobCommand(
                    "job-video-1",
                    MediaType.VIDEO,
                    "seedance-2.0",
                    "海边日落",
                    Instant.parse("2026-09-04T09:01:00Z")));

    assertEquals(MediaJobStatus.PREPARING, job.status(), "video status");
    assertEquals(MediaType.VIDEO, job.mediaType(), "video type");
    assertSame(job, store.saved.get(0), "video saved aggregate");
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

  private static final class RecordingStore implements MediaJobSubmissionStore {

    private final List<MediaJob> saved = new ArrayList<>();

    @Override
    public void save(MediaJob job) {
      saved.add(job);
    }
  }
}
