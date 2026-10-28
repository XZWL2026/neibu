package com.xzwl.cloud.storage.application.file;

public final class StoredFileNotFoundException extends RuntimeException {
  public StoredFileNotFoundException(String fileId) {
    super("file not found: " + fileId);
  }
}
