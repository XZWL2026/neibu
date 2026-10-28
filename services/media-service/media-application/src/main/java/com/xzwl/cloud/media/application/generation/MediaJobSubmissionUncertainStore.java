package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for the ambiguous submission state. */
public interface MediaJobSubmissionUncertainStore {

  MediaJob load(String jobId);

  void save(MediaJob job);
}
