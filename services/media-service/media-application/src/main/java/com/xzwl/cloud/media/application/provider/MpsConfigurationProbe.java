package com.xzwl.cloud.media.application.provider;

import java.util.List;

/** Read-only metadata preflight; never submits a media processing task. */
public interface MpsConfigurationProbe {
  List<MpsConfigurationStore.EnhancementTemplate> templates(MpsConfigurationStore.Configuration configuration);
  Result test(long expectedVersion,String templateId,String aspectRatio);
  record Result(long version,boolean success,String checkedAt,long durationMs,int width,int height,
      List<Long> definitions,Integer videoBitrateKbps,String message) {}
}
