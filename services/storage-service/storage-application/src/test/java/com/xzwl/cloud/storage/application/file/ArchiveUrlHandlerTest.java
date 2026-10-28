package com.xzwl.cloud.storage.application.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArchiveUrlHandlerTest {
  private static final byte[] MP4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

  @Test
  void streamsHttpsResultAndClosesRemoteResponse() {
    var remote = new FakeRemoteFileSource();
    var objects = new FakeObjectStorage();
    var handler = new ArchiveUrlHandler(remote, new StoreFileHandler(
        objects, new MemoryMetadataStore(), () -> "result-1",
        Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneOffset.UTC), 1024), (key, upload) -> upload.apply("archive-" + "a".repeat(64)));

    var stored = handler.handle("media-owner", "https://provider.example/result.mp4");

    assertEquals(FilePurpose.MEDIA_RESULT, stored.purpose());
    assertEquals("video/mp4", stored.mimeType());
    assertEquals(MP4.length, objects.bytesRead);
    assertTrue(remote.closed);
  }

  @Test
  void rejectsNonHttpsAndInvalidUrlsBeforeOpeningConnection() {
    var remote = new FakeRemoteFileSource();
    var handler = new ArchiveUrlHandler(remote, new StoreFileHandler(
        new FakeObjectStorage(), new MemoryMetadataStore(), () -> "result-1",
        Clock.systemUTC(), 1024), (key, upload) -> upload.apply("archive-" + "a".repeat(64)));

    assertThrows(FileValidationException.class,
        () -> handler.handle("owner", "http://provider.example/result.mp4"));
    assertThrows(FileValidationException.class,
        () -> handler.handle("owner", "data:video/mp4;base64,AAAA"));
    assertThrows(FileValidationException.class,
        () -> handler.handle("owner", "not a url"));
    assertThrows(FileValidationException.class,
        () -> handler.handle("", "https://provider.example/result.mp4"));
    assertThrows(FileValidationException.class,
        () -> handler.handle("owner", null));
    assertFalse(remote.opened);
  }

  @Test void identityIsIndependentFromSignedDownloadUrlAndLegacyIdentityIsUnchanged() {
    var keys = new java.util.ArrayList<String>();
    var source = new FakeRemoteFileSource();
    var store = new StoreFileHandler(new FakeObjectStorage(), new MemoryMetadataStore(),
        () -> "unused", Clock.systemUTC(), 1024);
    var subject = new ArchiveUrlHandler(source, store, (key, upload) -> {
      keys.add(key);
      return upload.apply("archive-" + "a".repeat(64));
    });
    String url = "https://provider.example/result.mp4?signature=one";
    subject.handle("owner", url);
    subject.handle("owner", url, null);
    assertEquals("5:owner" + url, keys.get(0));
    assertEquals(keys.get(0), keys.get(1));
    subject.handle("owner", url, "task:RESULT:0");
    subject.handle("owner", url.replace("one", "two"), "task:RESULT:0");
    assertEquals(keys.get(2), keys.get(3));
    org.junit.jupiter.api.Assertions.assertNotEquals(keys.get(0), keys.get(2));
    for (String invalid : java.util.List.of("", " ", "bad/key", "bad?signature=1", "x".repeat(257)))
      assertThrows(FileValidationException.class, () -> subject.handle("owner", url, invalid));
    assertEquals(4, keys.size(), "invalid keys must not reach the archive callback");
  }

  private static final class FakeRemoteFileSource implements RemoteFileSource {
    private boolean opened;
    private boolean closed;

    @Override
    public RemoteFile open(URI source) {
      opened = true;
      return new RemoteFile() {
        @Override public String mimeType() { return "video/mp4"; }
        @Override public long contentLength() { return MP4.length; }
        @Override public InputStream body() { return new ByteArrayInputStream(MP4); }
        @Override public void close() { closed = true; }
      };
    }
  }

  private static final class FakeObjectStorage implements PrivateObjectStorage {
    private long bytesRead;

    @Override
    public String putPrivate(String objectKey, String mimeType, long contentLength, InputStream body) {
      try {
        bytesRead = body.transferTo(OutputStream.nullOutputStream());
        return "etag";
      } catch (java.io.IOException failed) {
        throw new FileStorageException("read failed", failed);
      }
    }

    @Override
    public StoredObject head(String objectKey) {
      return bytesRead == 0 ? null : new StoredObject(objectKey, "video/mp4", bytesRead, "etag");
    }

    @Override public void delete(String objectKey) {}
    @Override public String signGet(String objectKey, long expiresSeconds) { return "unused"; }
  }

  private static final class MemoryMetadataStore implements FileMetadataStore {
    @Override public void save(com.xzwl.cloud.storage.domain.file.StoredFile file) {}
    @Override public Optional<com.xzwl.cloud.storage.domain.file.StoredFile> findById(String fileId) {
      return Optional.empty();
    }
  }
}
