package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.MediaReference;
import com.xzwl.cloud.media.domain.task.MediaTask;
import com.xzwl.cloud.media.domain.task.ReferenceMode;
import com.xzwl.cloud.media.domain.task.TaskData;
import com.xzwl.cloud.media.domain.task.TaskOperation;
import com.xzwl.cloud.media.domain.task.TaskRequest;
import com.xzwl.cloud.media.domain.task.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owns accepted enhancement identities and keeps new processing attached to its native output. */
public final class EnhancementTasks {
  private final MediaTaskStore store;
  private final EnhancementQuoteStore quotes;
  private final EnhancementQuoteService verification;
  private final EnhancementProvider provider;
  private final Clock clock;
  private final TaskPolicy policy;

  public EnhancementTasks(MediaTaskStore store, EnhancementQuoteStore quotes,
      EnhancementQuoteService verification, EnhancementProvider provider, Clock clock, TaskPolicy policy) {
    this.store = store;
    this.quotes = quotes;
    this.verification = verification;
    this.provider = provider;
    this.clock = clock;
    this.policy = policy;
  }

  public MediaTask submit(String owner, String source, int index, String key, String quoteId) {
    MediaReference.identifier(key, "Idempotency-Key");
    MediaReference.identifier(quoteId, "quoteId");
    // Resolve durable request aliases before quote expiry or configuration revalidation.
    var replay = store.replayEnhancement(owner, source, index, key, quoteId);
    if (replay.isPresent()) return replay.get();

    var offered = quotes.find(owner, quoteId)
        .orElseThrow(() -> new TaskNotFoundException("quote not found"));
    if (!source.equals(offered.source().taskId()) || index != offered.source().outputIndex()) {
      throw new TaskConflictException("quote belongs to another native output");
    }
    return store.createEnhancement(owner, source, index, key, quoteId, offered.targetResolution(), () -> {
      var quote = verification.confirmed(owner, quoteId);
      Instant now = clock.instant();
      String id = UUID.randomUUID().toString();
      var request = new TaskRequest(MediaType.VIDEO, "video-enhancement", "超分增强",
          quote.source().aspectRatio(), quote.targetResolution(), 1, quote.source().billableSeconds(),
          List.of(), ReferenceMode.FIRST_LAST_FRAME, TaskOperation.ENHANCEMENT);
      provider.prepare(id, quote);
      var task = new MediaTask(id, owner, key, TaskStatus.CHARGE_PENDING,
          new TaskData(request, null, null, null, null, null, null), now,
          now.plus(policy.videoTimeout()), now, 0);
      return new MediaTaskStore.EnhancementCreation(task, quote);
    });
  }

  public EnhancementQuoteStore.Quote quote(String id) {
    return store.enhancementQuote(id)
        .orElseThrow(() -> new TaskNotFoundException("enhancement not found"));
  }

  public List<MediaTask> list(String owner, String source) {
    return store.enhancements(owner, source);
  }

  public String submit(MediaTask task) {
    return provider.submit(task, quote(task.id()));
  }

  public ProviderPort.ProviderPoll poll(MediaTask task) {
    return provider.poll(task);
  }
}
