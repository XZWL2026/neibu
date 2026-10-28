package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for applying a provider completion to a media job. */
public interface MediaJobCompletionStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
