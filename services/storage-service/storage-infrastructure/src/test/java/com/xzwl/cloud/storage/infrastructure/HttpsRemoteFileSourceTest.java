package com.xzwl.cloud.storage.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.application.file.FileValidationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.Test;

class HttpsRemoteFileSourceTest {
  @Test
  void appliesTimeoutsAndAllowsOnlyBoundedAllowlistedHttpsRedirects() throws Exception {
    var redirect = new FakeConnection(new URL("https://provider.example/result"), 302,
        "https://cdn.example/result", null, -1, new byte[0]);
    var finalResponse = new FakeConnection(new URL("https://cdn.example/result"), 200,
        null, "video/mp4", 12, new byte[12]);
    Queue<FakeConnection> responses = new ArrayDeque<>();
    responses.add(redirect);
    responses.add(finalResponse);
    var source = new HttpsRemoteFileSource(
        Set.of("provider.example", "cdn.example"), 1200, 3400, 1, 100,
        url -> responses.remove());

    try (var remote = source.open(URI.create("https://provider.example/result"))) {
      assertEquals("video/mp4", remote.mimeType());
      assertEquals(12, remote.contentLength());
    }

    assertEquals(1200, redirect.getConnectTimeout());
    assertEquals(3400, redirect.getReadTimeout());
    assertFalse(redirect.getInstanceFollowRedirects());
    assertEquals(1200, finalResponse.getConnectTimeout());
    assertFalse(finalResponse.getInstanceFollowRedirects());
  }

  @Test
  void rejectsDisallowedHostsRedirectOverflowAndMissingContentLength() throws Exception {
    var factory = new QueueFactory();
    var source = new HttpsRemoteFileSource(
        Set.of("provider.example"), 1000, 2000, 0, 100, factory);
    assertThrows(FileValidationException.class,
        () -> source.open(URI.create("https://evil.example/result")));

    factory.add(new FakeConnection(new URL("https://provider.example/result"), 302,
        "https://provider.example/next", null, -1, new byte[0]));
    assertThrows(FileStorageException.class,
        () -> source.open(URI.create("https://provider.example/result")));

    factory.add(new FakeConnection(new URL("https://provider.example/result"), 200,
        null, "image/png", -1, new byte[0]));
    assertThrows(FileStorageException.class,
        () -> source.open(URI.create("https://provider.example/result")));
  }

  @Test
  void readsChunkedImageWithMeasuredLengthAndRejectsEmptyOrOversizedBodies() throws Exception {
    var factory=new QueueFactory();
    var source=new HttpsRemoteFileSource(Set.of("provider.example"),1000,2000,0,8,factory);
    factory.add(new FakeConnection(new URL("https://provider.example/result"),200,null,"image/png",-1,new byte[]{1,2,3,4}));
    try(var remote=source.open(URI.create("https://provider.example/result"))) {
      assertEquals(4,remote.contentLength());
      assertEquals(4,remote.body().readAllBytes().length);
      assertEquals("image/png",remote.mimeType());
    }
    factory.add(new FakeConnection(new URL("https://provider.example/result"),200,null,"image/png",-1,new byte[9]));
    assertThrows(FileValidationException.class,()->source.open(URI.create("https://provider.example/result")));
    factory.add(new FakeConnection(new URL("https://provider.example/result"),200,null,"image/png",-1,new byte[0]));
    assertThrows(FileStorageException.class,()->source.open(URI.create("https://provider.example/result")));
  }

  private static final class QueueFactory implements HttpsRemoteFileSource.ConnectionFactory {
    private final Queue<FakeConnection> responses = new ArrayDeque<>();

    void add(FakeConnection connection) {
      responses.add(connection);
    }

    @Override
    public HttpsURLConnection open(URL url) {
      return responses.remove();
    }
  }

  private static final class FakeConnection extends HttpsURLConnection {
    private final int status;
    private final String location;
    private final String contentType;
    private final long contentLength;
    private final byte[] content;

    FakeConnection(
        URL url, int status, String location, String contentType, long contentLength,
        byte[] content) {
      super(url);
      this.status = status;
      this.location = location;
      this.contentType = contentType;
      this.contentLength = contentLength;
      this.content = content;
    }

    @Override public int getResponseCode() { return status; }
    @Override public String getHeaderField(String name) {
      return "Location".equalsIgnoreCase(name) ? location : null;
    }
    @Override public String getContentType() { return contentType; }
    @Override public long getContentLengthLong() { return contentLength; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void disconnect() {}
    @Override public boolean usingProxy() { return false; }
    @Override public void connect() {}
    @Override public String getCipherSuite() { return "unused"; }
    @Override public Certificate[] getLocalCertificates() { return null; }
    @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
      return null;
    }
    @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return null; }
    @Override public Principal getLocalPrincipal() { return null; }
  }
}
