package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for provider acceptance updates. */
public interface MediaJobAcceptanceStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
