package com.xzwl.cloud.storage.application.file;

import java.io.InputStream;

public interface PrivateObjectStorage {
  String putPrivate(String objectKey, String mimeType, long contentLength, InputStream body);

  StoredObject head(String objectKey);

  default java.util.Optional<StoredObject> find(String objectKey) {
    return java.util.Optional.ofNullable(head(objectKey));
  }

  default InputStream open(String objectKey) {
    throw new FileValidationException("reference metadata unavailable; upload the file again");
  }

  void delete(String objectKey);

  String signGet(String objectKey, long expiresSeconds);

  /** Sign an attachment response with an explicit filename, including its extension. */
  default String signGet(String objectKey, long expiresSeconds, String downloadFilename) {
    throw new FileStorageException("signed attachment downloads are not supported");
  }

  record StoredObject(String objectKey, String contentType, long contentLength, String etag) {}
}
