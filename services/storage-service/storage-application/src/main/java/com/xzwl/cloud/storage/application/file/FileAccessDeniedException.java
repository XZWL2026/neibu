package com.xzwl.cloud.storage.application.file;

public final class FileAccessDeniedException extends RuntimeException {
  public FileAccessDeniedException() {
    super("file does not belong to requester");
  }
}
