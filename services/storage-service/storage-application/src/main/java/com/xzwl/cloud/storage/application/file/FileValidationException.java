package com.xzwl.cloud.storage.application.file;

public final class FileValidationException extends RuntimeException {
  public FileValidationException(String message) {
    super(message);
  }

  public FileValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
