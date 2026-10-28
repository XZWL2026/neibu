package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Debits first, submits once, and refunds a definite provider rejection. */
public final class SubmitPreparedMediaHandler {

  private final MediaBillingPort billing;
  private final MediaProviderSubmissionPort provider;
  private final MediaJobSubmissionStore store;

  public SubmitPreparedMediaHandler(
      MediaBillingPort billing,
      MediaProviderSubmissionPort provider,
      MediaJobSubmissionStore store) {
    this.billing = Objects.requireNonNull(billing, "billing");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(SubmitPreparedMediaCommand command) {
    SubmitPreparedMediaCommand requested = Objects.requireNonNull(command, "command");
    if (requested.operationId() == null || requested.operationId().isBlank()) {
      throw new IllegalArgumentException("operationId must not be blank");
    }
    MediaJob job = Objects.requireNonNull(requested.job(), "job");
    MediaGenerationPreparationResult preparation = Objects.requireNonNull(requested.preparation(), "preparation");
    if (preparation.status() != MediaGenerationPreparationResult.Status.READY) {
      throw new IllegalArgumentException("media preparation must be ready");
    }
    MediaChannelSelection channel = Objects.requireNonNull(preparation.channel(), "channel");
    BigDecimal cost = Objects.requireNonNull(preparation.cost(), "cost").setScale(4, RoundingMode.DOWN);

    billing.debit(requested.operationId(), cost);
    try {
      String providerTaskId = provider.submit(job, channel);
      job.accept(providerTaskId, requested.acceptedAt());
      store.save(job);
      return job;
    } catch (RuntimeException rejected) {
      billing.refund(requested.operationId(), cost);
      throw rejected;
    }
  }
}
