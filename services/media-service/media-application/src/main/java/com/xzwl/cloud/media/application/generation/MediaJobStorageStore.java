package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for applying storage archival completion to a media job. */
public interface MediaJobStorageStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
