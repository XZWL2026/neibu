package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Persistence port for the initial media generation intent. */
public interface MediaJobSubmissionStore {

  void save(MediaJob job);
}
