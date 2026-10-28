package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for recording a media provider failure. */
public interface MediaJobFailureStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
