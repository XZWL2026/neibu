package com.xzwl.cloud.storage.application.file;

import java.io.InputStream;
import java.net.URI;

public interface RemoteFileSource {
  RemoteFile open(URI source);

  interface RemoteFile extends AutoCloseable {
    String mimeType();

    long contentLength();

    InputStream body();

    @Override
    void close();
  }
}
