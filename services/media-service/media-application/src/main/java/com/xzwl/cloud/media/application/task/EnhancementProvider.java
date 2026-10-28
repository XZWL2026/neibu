package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.MediaTask;

/** An accepted enhancement has a frozen MPS plan and never calls a generation provider. */
public interface EnhancementProvider {
  void prepare(String id, EnhancementQuoteStore.Quote quote);
  String submit(MediaTask task, EnhancementQuoteStore.Quote quote);
  ProviderPort.ProviderPoll poll(MediaTask task);
}
