package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;
import java.time.Instant;

public final class ApplyVideoProviderCompletionHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-04T07:00:01Z");
  private static final Instant ROUTED_AT = Instant.parse("2026-09-04T07:00:02Z");

  public static void main(String[] args) {
    queues480pForStorage();
    replaysNativeProviderCompletionAfterStorageArchive();
    queues1080pSourceForStorage();
    repeatsEnhancementSourceRoutingIdempotently();
    ignoresLateCompletionAfterFailure();
    ignoresLateCompletionAfterEnhancementStorageQueued();
    System.out.println("ApplyVideoProviderCompletionHandlerTest passed: 6");
  }

  private static void queues480pForStorage() {
    RecordingStore store = new RecordingStore(acceptedJob("job-480"));
    MediaJob result = handler(store).handle(command("job-480", VideoResolution.R480P, "tos://480"));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "480p status");
    assertEquals("tos://480", result.storageSourceUrl(), "480p storage source");
    assertEquals(ROUTED_AT, result.storageQueuedAt(), "480p storage queuedAt");
    assertEquals(1, store.saveCount, "480p save count");
  }

  private static void replaysNativeProviderCompletionAfterStorageArchive() {
    RecordingStore store = new RecordingStore(acceptedJob("job-480-archived"));
    ApplyVideoProviderCompletionHandler handler = handler(store);
    ApplyVideoProviderCompletionCommand command =
        command("job-480-archived", VideoResolution.R480P, "provider://480");

    handler.handle(command);
    new CompleteMediaStorageHandler(new StorageStoreAdapter(store)).handle(
        new CompleteMediaStorageCommand("job-480-archived", "tos://480-archived", ROUTED_AT));

    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.COMPLETED, repeated.status(), "archived 480p replay status");
    assertEquals("tos://480-archived", repeated.outputUrl(), "archived 480p replay output");
    assertEquals(2, store.saveCount, "archived 480p replay save count");
  }

  private static void queues1080pSourceForStorage() {
    RecordingStore store = new RecordingStore(acceptedJob("job-1080"));
    MediaJob result = handler(store).handle(command("job-1080", VideoResolution.R1080P, "tos://720"));

    assertEquals(MediaJobStatus.STORAGE_PENDING, result.status(), "1080p status");
    assertEquals("tos://720", result.storageSourceUrl(), "1080p storage source");
    assertEquals(ROUTED_AT, result.storageQueuedAt(), "1080p storage queuedAt");
    assertEquals(1, store.saveCount, "1080p save count");
  }

  private static void repeatsEnhancementSourceRoutingIdempotently() {
    RecordingStore store = new RecordingStore(acceptedJob("job-repeat"));
    ApplyVideoProviderCompletionHandler handler = handler(store);
    VideoDeliveryPlan plan = plan(VideoResolution.R720P);
    ApplyVideoProviderCompletionCommand command =
        new ApplyVideoProviderCompletionCommand("job-repeat", plan, "tos://480", ROUTED_AT);

    handler.handle(command);
    MediaJob repeated = handler.handle(command);

    assertEquals(MediaJobStatus.STORAGE_PENDING, repeated.status(), "repeated status");
    assertEquals("tos://480", repeated.storageSourceUrl(), "repeated storage source");
    assertEquals(1, store.saveCount, "repeated save count");
  }

  private static void ignoresLateCompletionAfterFailure() {
    MediaJob job = acceptedJob("job-failed");
    job.fail("provider timed out", ROUTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(command("job-failed", VideoResolution.R480P, "tos://late"));

    assertEquals(MediaJobStatus.FAILED, result.status(), "late video status");
    assertEquals("provider timed out", result.failureMessage(), "late video failure");
    assertEquals(0, store.saveCount, "late video save count");
  }

  private static void ignoresLateCompletionAfterEnhancementStorageQueued() {
    MediaJob job = acceptedJob("job-enhancement-storage");
    job.queueEnhancement("tos://720", ROUTED_AT);
    job.queueEnhancementOutputStorage("tos://1080", ROUTED_AT);
    RecordingStore store = new RecordingStore(job);

    MediaJob result = handler(store).handle(
        command("job-enhancement-storage", VideoResolution.R1080P, "tos://late"));

    assertEquals(MediaJobStatus.ENHANCEMENT_STORAGE_PENDING, result.status(), "late enhancement status");
    assertEquals(0, store.saveCount, "late enhancement save count");
  }

  private static ApplyVideoProviderCompletionHandler handler(RecordingStore store) {
    return new ApplyVideoProviderCompletionHandler(store);
  }

  private static ApplyVideoProviderCompletionCommand command(
      String jobId,
      VideoResolution targetResolution,
      String outputUrl) {
    return new ApplyVideoProviderCompletionCommand(jobId, plan(targetResolution), outputUrl, ROUTED_AT);
  }

  private static VideoDeliveryPlan plan(VideoResolution targetResolution) {
    return new PlanVideoDeliveryHandler().handle(
        new PlanVideoDeliveryCommand(targetResolution));
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

  private static final class RecordingStore implements MediaJobCompletionStore {

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

  private static final class StorageStoreAdapter implements MediaJobStorageStore {

    private final RecordingStore delegate;

    private StorageStoreAdapter(RecordingStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public MediaJob load(String jobId) {
      return delegate.load(jobId);
    }

    @Override
    public void save(MediaJob job) {
      delegate.save(job);
    }
  }
}
