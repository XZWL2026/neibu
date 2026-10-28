package com.xzwl.cloud.storage.infrastructure;

import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.application.file.FileValidationException;
import com.xzwl.cloud.storage.application.file.RemoteFileSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URL;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.HttpsURLConnection;

public final class HttpsRemoteFileSource implements RemoteFileSource {
  private final Set<String> allowedHosts;
  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;
  private final int maxRedirects;
  private final ConnectionFactory connections;
  private final long maxBytes;

  public HttpsRemoteFileSource(
      Set<String> allowedHosts,
      int connectTimeoutMillis,
      int readTimeoutMillis,
      int maxRedirects, long maxBytes) {
    this(allowedHosts, connectTimeoutMillis, readTimeoutMillis, maxRedirects, maxBytes,
        url -> (HttpsURLConnection) url.openConnection());
  }

  HttpsRemoteFileSource(
      Set<String> allowedHosts,
      int connectTimeoutMillis,
      int readTimeoutMillis,
      int maxRedirects, long maxBytes,
      ConnectionFactory connections) {
    Objects.requireNonNull(allowedHosts, "allowedHosts");
    this.allowedHosts = allowedHosts.stream()
        .map(host -> Objects.requireNonNull(host, "allowed host").trim().toLowerCase(Locale.ROOT))
        .filter(host -> !host.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
    // An unconfigured allowlist denies every request in requireAllowed, before opening a connection.
    if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || maxRedirects < 0) {
      throw new IllegalArgumentException("timeouts must be positive and maxRedirects non-negative");
    }
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.readTimeoutMillis = readTimeoutMillis;
    this.maxRedirects = maxRedirects;
    if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
    this.maxBytes = maxBytes;
    this.connections = Objects.requireNonNull(connections, "connections");
  }

  @Override
  public RemoteFile open(URI source) {
    Objects.requireNonNull(source, "source");
    URI current = source;
    int redirects = 0;
    while (true) {
      requireAllowed(current);
      HttpsURLConnection connection = openConnection(current);
      configure(connection);
      int status = responseCode(connection);
      if (isRedirect(status)) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (redirects >= maxRedirects) {
          throw new FileStorageException("archive URL exceeded the redirect limit");
        }
        if (location == null || location.isBlank()) {
          throw new FileStorageException("archive URL redirect has no Location header");
        }
        try {
          current = current.resolve(location);
        } catch (IllegalArgumentException invalidLocation) {
          throw new FileStorageException("archive URL redirect is invalid", invalidLocation);
        }
        redirects++;
        continue;
      }
      if (status < 200 || status >= 300) {
        connection.disconnect();
        throw new FileStorageException("archive URL returned HTTP " + status);
      }

      long contentLength = connection.getContentLengthLong();
      if (contentLength > maxBytes) {
        connection.disconnect();
        throw new FileValidationException("file size is outside the allowed range");
      }
      if (contentLength < 0) return spool(connection, normalizeContentType(connection.getContentType()));
      if (contentLength == 0) {
        connection.disconnect();
        throw new FileStorageException("archive URL must provide a positive Content-Length");
      }
      String contentType = normalizeContentType(connection.getContentType());
      try {
        InputStream body = connection.getInputStream();
        return new ConnectedRemoteFile(connection, body, contentType, contentLength);
      } catch (IOException failed) {
        connection.disconnect();
        throw new FileStorageException("archive URL response could not be opened", failed);
      }
    }
  }

  /** Chunked CDN responses need a measured length for the existing object-storage API. */
  private RemoteFile spool(HttpsURLConnection connection, String mimeType) {
    Path temporary = null;
    try {
      temporary = Files.createTempFile("xzwl-media-archive-", ".tmp");
      long length = 0;
      try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          length += read;
          if (length > maxBytes) throw new FileValidationException("file size is outside the allowed range");
          output.write(buffer, 0, read);
        }
      }
      if (length == 0) throw new FileStorageException("archive URL returned an empty body");
      InputStream body = Files.newInputStream(temporary, StandardOpenOption.DELETE_ON_CLOSE);
      temporary = null;
      return new ConnectedRemoteFile(connection, body, mimeType, length);
    } catch (IOException failed) {
      throw new FileStorageException("archive URL response could not be buffered", failed);
    } finally {
      connection.disconnect();
      if (temporary != null) try { Files.deleteIfExists(temporary); }
      catch (IOException failed) { throw new FileStorageException("archive temporary file cleanup failed", failed); }
    }
  }

  private void requireAllowed(URI source) {
    String host = source.getHost();
    if (!"https".equalsIgnoreCase(source.getScheme())
        || source.getUserInfo() != null
        || host == null
        || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
      throw new FileValidationException("archive URL host is not allowed");
    }
  }

  private HttpsURLConnection openConnection(URI source) {
    try {
      return connections.open(source.toURL());
    } catch (IOException | ClassCastException failed) {
      throw new FileStorageException("archive URL connection could not be opened", failed);
    }
  }

  private void configure(HttpsURLConnection connection) {
    connection.setConnectTimeout(connectTimeoutMillis);
    connection.setReadTimeout(readTimeoutMillis);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Accept", "image/jpeg,image/png,video/mp4");
    try {
      connection.setRequestMethod("GET");
    } catch (java.net.ProtocolException failed) {
      connection.disconnect();
      throw new FileStorageException("archive URL request could not be configured", failed);
    }
  }

  private static int responseCode(HttpsURLConnection connection) {
    try {
      return connection.getResponseCode();
    } catch (IOException failed) {
      connection.disconnect();
      throw new FileStorageException("archive URL request failed", failed);
    }
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  private static String normalizeContentType(String contentType) {
    if (contentType == null) return "";
    int parameter = contentType.indexOf(';');
    String mediaType = parameter < 0 ? contentType : contentType.substring(0, parameter);
    return mediaType.trim().toLowerCase(Locale.ROOT);
  }

  @FunctionalInterface
  interface ConnectionFactory {
    HttpsURLConnection open(URL url) throws IOException;
  }

  private record ConnectedRemoteFile(
      HttpsURLConnection connection,
      InputStream body,
      String mimeType,
      long contentLength) implements RemoteFile {
    @Override
    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
        // The object upload has already completed or failed; disconnect still must run.
      } finally {
        connection.disconnect();
      }
    }
  }
}
