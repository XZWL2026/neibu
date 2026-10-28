package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.*;
import java.time.Instant;
import java.util.*;

public interface MediaTaskStore {
  MediaTask create(MediaTask task);
  record EnhancementCreation(MediaTask task,EnhancementQuoteStore.Quote quote) {}
  default Optional<MediaTask> replayEnhancement(String owner,String source,int index,String key,String quoteId) { return Optional.empty(); }
  default MediaTask createEnhancement(String owner,String source,int index,String key,String quoteId,String target,java.util.function.Supplier<EnhancementCreation> create) { throw new UnsupportedOperationException(); }
  default Optional<EnhancementQuoteStore.Quote> enhancementQuote(String id) { return Optional.empty(); }
  default List<MediaTask> enhancements(String owner,String source) { return List.of(); }
  default void deleteHistory(String owner,String id) { throw new UnsupportedOperationException(); }
  default Optional<MediaTask> replayRegeneration(String owner,String sourceId,String key) { return Optional.empty(); }
  default MediaTask regenerate(String sourceId,MediaTask candidate) { throw new UnsupportedOperationException(); }
  Optional<MediaTask> find(String id);
  default boolean isHistoryVisible(String owner,String id) { return find(id).filter(t->t.ownerId().equals(owner)).isPresent(); }
  default Optional<MediaTask> findRequest(String owner,String key) { return Optional.empty(); }
  default List<MediaTask> list(String owner,int limit,String beforeId,boolean excludeCanvas) {
    return list(owner,limit,beforeId,excludeCanvas,false);
  }
  List<MediaTask> list(String owner,int limit,String beforeId,boolean excludeCanvas,boolean commerceOnly);
  List<String> due(Instant now, int limit);
  Optional<Lease> claim(String id, String token, Instant now, Instant until);
  /** Atomically CAS the task, append a state record and save any output records. */
  void save(Lease lease, MediaTask next, List<TaskResult> results, Instant now);
  List<TaskResult> results(String taskId);
  record Lease(MediaTask task, String token) {}
}
