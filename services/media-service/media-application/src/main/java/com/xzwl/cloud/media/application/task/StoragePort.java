package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.MediaReference;
import java.util.List;

public interface StoragePort {
  List<MediaReference> resolveReferences(String ownerId, List<MediaReference> references);
  default List<MediaReference> resolveReferences(String ownerId, List<MediaReference> references,
      com.xzwl.cloud.media.domain.task.ReferenceMode mode) { return resolveReferences(ownerId,references); }
  String archive(String ownerId,String taskId,int index,String httpsUrl);
  default VideoSource probeVideo(String ownerId,String fileId) { throw new DependencyUnavailableException("video probe unavailable"); }
  record VideoSource(String url,String mimeType,Double durationSeconds,Integer width,Integer height) {}
  enum ArchiveStage { NATIVE, ENHANCEMENT, RESULT }
  default String archive(String ownerId,String taskId,int index,String httpsUrl,ArchiveStage stage) {
    return archive(ownerId,taskId,index,httpsUrl);
  }
}
