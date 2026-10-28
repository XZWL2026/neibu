package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SubmitPreparedMediaHandlerTest {

  public static void main(String[] args) {
    debitsBeforeSubmittingToTheSelectedProvider();
    refundsWhenProviderRejectsAfterDebit();
    System.out.println("SubmitPreparedMediaHandlerTest passed: 2");
  }

  private static void debitsBeforeSubmittingToTheSelectedProvider() {
    RecordingBilling billing = new RecordingBilling();
    RecordingProvider provider = new RecordingProvider();
    RecordingStore store = new RecordingStore();
    MediaJob job = MediaJob.prepare("job-1", MediaType.IMAGE, "model", "prompt", at(0));
    MediaGenerationPreparationResult prepared = MediaGenerationPreparationResult.ready(
        new MediaChannelSelection(1L, 2L, "model", "upstream-model"), new BigDecimal("3.0000"));

    MediaJob result = new SubmitPreparedMediaHandler(billing, provider, store).handle(
        new SubmitPreparedMediaCommand("operation-1", job, prepared, at(1)));

    check(result.status() == MediaJobStatus.PROVIDER_ACCEPTED, "job is accepted after provider submit");
    check(result.providerTaskId().equals("provider-task-1"), "provider task is recorded");
    check(billing.events.equals(List.of("debit:operation-1:3.0000")), "debit is recorded");
    check(provider.events.equals(List.of("submit:1")), "provider is called once");
    check(store.saved == result, "accepted job is persisted");
    check(billing.events.getFirst().startsWith("debit"), "debit precedes submission");
  }

  private static void refundsWhenProviderRejectsAfterDebit() {
    RecordingBilling billing = new RecordingBilling();
    RecordingProvider provider = new RecordingProvider();
    provider.reject = true;
    MediaJob job = MediaJob.prepare("job-1", MediaType.VIDEO, "model", "prompt", at(0));
    MediaGenerationPreparationResult prepared = MediaGenerationPreparationResult.ready(
        new MediaChannelSelection(1L, 2L, "model", "upstream-model"), new BigDecimal("4.0000"));

    try {
      new SubmitPreparedMediaHandler(billing, provider, new RecordingStore()).handle(
          new SubmitPreparedMediaCommand("operation-1", job, prepared, at(1)));
      throw new AssertionError("provider rejection should be propagated");
    } catch (IllegalStateException expected) {
      check(billing.events.equals(List.of("debit:operation-1:4.0000", "refund:operation-1:4.0000")),
          "rejection refunds original debit");
      check(job.status() == MediaJobStatus.PREPARING, "rejected job is not accepted");
    }
  }

  private static Instant at(long seconds) {
    return Instant.parse("2026-09-05T00:00:00Z").plusSeconds(seconds);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingBilling implements MediaBillingPort {
    private final List<String> events = new ArrayList<>();

    @Override
    public void debit(String operationId, BigDecimal amount) {
      events.add("debit:" + operationId + ":" + amount.setScale(4));
    }

    @Override
    public void refund(String operationId, BigDecimal amount) {
      events.add("refund:" + operationId + ":" + amount.setScale(4));
    }
  }

  private static final class RecordingProvider implements MediaProviderSubmissionPort {
    private final List<String> events = new ArrayList<>();
    private boolean reject;

    @Override
    public String submit(MediaJob job, MediaChannelSelection channel) {
      events.add("submit:" + channel.channelId());
      if (reject) throw new IllegalStateException("provider rejected submission");
      return "provider-task-1";
    }
  }

  private static final class RecordingStore implements MediaJobSubmissionStore {
    private MediaJob saved;

    @Override
    public void save(MediaJob job) {
      saved = job;
    }
  }
}
