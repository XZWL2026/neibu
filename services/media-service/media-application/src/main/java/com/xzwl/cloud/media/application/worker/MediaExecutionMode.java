package com.xzwl.cloud.media.application.worker;

/** Whether a media provider operation is completed inline or through polling. */
public enum MediaExecutionMode {
  SYNC,
  ASYNC
}
