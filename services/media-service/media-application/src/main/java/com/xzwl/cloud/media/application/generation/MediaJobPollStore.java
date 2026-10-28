package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for applying normalized provider polling outcomes. */
public interface MediaJobPollStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
