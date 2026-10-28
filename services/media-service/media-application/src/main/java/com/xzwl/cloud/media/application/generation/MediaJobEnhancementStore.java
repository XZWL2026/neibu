package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for finalizing a queued video enhancement. */
public interface MediaJobEnhancementStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
