package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;

/** Provider adapter boundary; implementations choose credentials behind the selected channel. */
public interface MediaProviderSubmissionPort {

  String submit(MediaJob job, MediaChannelSelection channel);
}
