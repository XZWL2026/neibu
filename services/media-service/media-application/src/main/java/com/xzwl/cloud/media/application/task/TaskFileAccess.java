package com.xzwl.cloud.media.application.task;
import java.time.Instant;
public interface TaskFileAccess {
  Access sign(String ownerId,String fileId);
  record Access(String url,Instant expiresAt) {}
}
