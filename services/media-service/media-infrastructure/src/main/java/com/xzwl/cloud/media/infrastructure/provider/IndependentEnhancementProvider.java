package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.task.DefinitiveRejectionException;
import com.xzwl.cloud.media.application.task.DependencyUnavailableException;
import com.xzwl.cloud.media.application.task.EnhancementProvider;
import com.xzwl.cloud.media.application.task.EnhancementQuoteStore;
import com.xzwl.cloud.media.application.task.ProviderPort;
import com.xzwl.cloud.media.application.task.StoragePort;
import com.xzwl.cloud.media.application.task.TaskConflictException;
import com.xzwl.cloud.media.domain.task.MediaTask;
import java.util.Objects;
import java.util.function.Supplier;

/** Durable MPS-only operation. SENDING is never reset after an ambiguous provider receipt. */
public final class IndependentEnhancementProvider implements EnhancementProvider {
  private final Supplier<ProviderSettings> settings;
  private final ProviderJournal journal;
  private final TencentMpsAdapter mps;
  private final StoragePort storage;

  public IndependentEnhancementProvider(Supplier<ProviderSettings> settings,
      ProviderJournal journal, TencentMpsAdapter mps, StoragePort storage) {
    this.settings = settings;
    this.journal = journal;
    this.mps = mps;
    this.storage = storage;
  }

  public void prepare(String id, EnhancementQuoteStore.Quote quote) {
    var live = settings.get();
    var selected = live.projectedTemplates().stream()
        .filter(t -> t.enabled() && t.id().equals(quote.templateId())
            && t.outputResolution().equals(quote.targetResolution())
            && t.acceptedInputResolutions().contains(quote.source().resolution()))
        .findFirst().orElseThrow(() -> new TaskConflictException("enhancement template unavailable"));
    var plan = mps.prepare(live.enhancementPlan(selected,
        quote.source().aspectRatio(), quote.source().resolution()), live);
    var accepted = quote.templateEvidence();
    var actual = plan.enhancement();
    if (actual.configurationVersion() != quote.templateVersion() || actual.width() != accepted.width()
        || actual.height() != accepted.height() || !actual.definitions().equals(accepted.definitions())
        || !Objects.equals(actual.videoBitrateKbps(), accepted.videoBitrateKbps())) {
      throw new TaskConflictException("enhancement template changed; obtain a new quote");
    }
    journal.createEnhancement(id, plan);
  }

  public String submit(MediaTask task, EnhancementQuoteStore.Quote quote) {
    var operation = journal.find(task.id());
    if (operation == null) throw new DependencyUnavailableException("enhancement intent unavailable");
    if (operation.phase().equals("FAILED")) throw new DefinitiveRejectionException("PROVIDER_REJECTED");
    if (!operation.phase().equals("MPS_READY")) return task.id();
    var live = settings.get();
    requireCredentials(live);

    // Renew only an unsent operation; never use URL expiry to resend MPS_SENDING.
    var source = storage.probeVideo(task.ownerId(), quote.source().fileId());
    var accepted = quote.source().video();
    if (!Objects.equals(source.width(), accepted.width())
        || !Objects.equals(source.height(), accepted.height())
        || !Objects.equals(source.durationSeconds(), accepted.durationSeconds())) {
      throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    }
    if (!journal.move(operation, "MPS_SENDING", null, source.url(), null)) throw ProviderHttp.uncertain();
    var sending = journal.find(task.id());
    try (var diagnostics = ProviderHttpDiagnostics.open(journal, task.id(),
        "MPS_SENDING", task.data().request(), live)) {
      try {
        String upstream = mps.submit(task.id(), source.url(), operation.plan(), live);
        journal.move(sending, "MPS_RUNNING", upstream, null, null);
      } catch (DefinitiveRejectionException rejected) {
        journal.fail(sending, "MPS_REJECTED");
        throw rejected;
      }
    }
    return task.id();
  }

  public ProviderPort.ProviderPoll poll(MediaTask task) {
    var operation = journal.find(task.id());
    if (operation == null) throw ProviderHttp.uncertain();
    if (operation.phase().equals("COMPLETE")) return ProviderPort.ProviderPoll.completed(operation.outputs());
    if (operation.phase().equals("FAILED")) return ProviderPort.ProviderPoll.failed();
    if (!operation.phase().equals("MPS_RUNNING")) return ProviderPort.ProviderPoll.pending();
    var live = settings.get();
    requireCredentials(live);
    try (var diagnostics = ProviderHttpDiagnostics.open(journal, task.id(),
        operation.phase(), task.data().request(), live)) {
      var result = mps.poll(operation.upstream(), operation.plan(), live);
      if (result.status() == ProviderPort.ProviderPoll.Status.PENDING) return result;
      boolean saved = result.status() == ProviderPort.ProviderPoll.Status.FAILED
          ? journal.fail(operation, "MPS_FAILED", result.failureStatus())
          : journal.move(operation, "COMPLETE", null, null, result.outputs(), null, null,
              result.enhancementOutputs());
      return saved ? result : ProviderPort.ProviderPoll.pending();
    } catch (DefinitiveRejectionException rejected) {
      journal.fail(operation, "MPS_REJECTED");
      return ProviderPort.ProviderPoll.failed();
    }
  }

  private void requireCredentials(ProviderSettings live) {
    if (live.secretId.isBlank() || live.secretKey.isBlank()) throw ProviderSettings.unavailable();
  }
}
