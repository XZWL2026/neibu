package com.xzwl.cloud.storage.interfaces.http;

import com.xzwl.cloud.storage.application.file.*;
import com.xzwl.cloud.storage.domain.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedReferenceMetadataTest {
  @Test void generatedImagesAndVideosAreInspectedOnceBeforeOmniSigning() throws Exception {
    var image = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB), "png", image);
    byte[] video;
    try (var input = getClass().getResourceAsStream("/reference-media/clip.mp4")) { video = input.readAllBytes(); }
    for (var mime : List.of("image/png", "video/mp4")) {
      var bytes = mime.startsWith("image") ? image.toByteArray() : video;
      var store = new MemoryMetadata(mime, bytes.length);
      var objects = new ObjectBytes(bytes);
      var handler = new CreateAccessUrlHandler(store, objects, Clock.systemUTC());
      assertThrows(FileAccessDeniedException.class, () -> handler.handleReferenceMedia("file", "other", true));
      assertEquals(0, objects.reads);
      var result = handler.handleReferenceMedia("file", "owner", true);
      assertNotNull(result.width()); assertNotNull(result.height());
      if (mime.startsWith("video")) { assertTrue(result.durationSeconds() >= 2); assertNotNull(result.fps()); }
      assertEquals(FilePurpose.MEDIA_RESULT, store.file.purpose());
      assertEquals("file", store.file.fileId());
      assertEquals(1, store.updates);
      handler.handleReferenceMedia("file", "owner", true);
      assertEquals(1, objects.reads);
    }
  }

  @Test void processingAcceptsLongLowFpsVideoButReferenceGateRemainsStrictAfterMetadataCache() throws Exception {
    var path=java.nio.file.Files.createTempFile("depth-storage-test-",".mp4");
    try {
      var process=new ProcessBuilder("ffmpeg","-v","error","-y","-f","lavfi","-i","color=s=320x180:r=2:d=31.5",
          "-f","lavfi","-i","sine=duration=33","-c:v","libx264","-c:a","aac",path.toString()).start();
      assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
      byte[] bytes=java.nio.file.Files.readAllBytes(path);
      var store=new MemoryMetadata("video/mp4",bytes.length);var objects=new ObjectBytes(bytes);
      var handler=new CreateAccessUrlHandler(store,objects,Clock.systemUTC());
      assertThrows(FileAccessDeniedException.class,()->handler.handleProcessingVideo("file","other"));
      assertEquals(0,objects.reads);
      var source=handler.handleProcessingVideo("file","owner");
      assertEquals(31.5,source.durationSeconds(),0.001); // Video track, not trailing audio/format duration.
      assertEquals(2,source.fps(),0.001);
      assertThrows(FileValidationException.class,()->handler.handleReferenceMedia("file","owner",true));
      assertEquals(1,objects.signs);
      store.file=new StoredFile("file","owner","objects/file","video/mp4",bytes.length,"etag",FilePurpose.PROCESSING_VIDEO,
          Instant.EPOCH,3.0,960,540,25.0);
      assertDoesNotThrow(()->handler.handleReferenceMedia("file","owner",true));
    } finally { java.nio.file.Files.deleteIfExists(path); }
  }

  @Test void invalidOrTruncatedObjectsAreNeverBackfilledOrSigned() {
    var store = new MemoryMetadata("image/png", 100);
    var objects = new ObjectBytes(new byte[] {1, 2, 3});
    var handler = new CreateAccessUrlHandler(store, objects, Clock.systemUTC());
    assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("file", "owner", true));
    assertEquals(0, store.updates); assertEquals(0, objects.signs);
  }

  static class MemoryMetadata implements FileMetadataStore {
    StoredFile file; int updates;
    MemoryMetadata(String mime, int bytes) { file = new StoredFile("file", "owner", "objects/file", mime, bytes, "etag", FilePurpose.MEDIA_RESULT, Instant.EPOCH); }
    public Optional<StoredFile> findById(String id) { return Optional.of(file); }
    public void save(StoredFile value) { throw new AssertionError("must preserve file identity"); }
    public void updateMediaMetadata(StoredFile value) { file = value; updates++; }
  }
  static class ObjectBytes implements PrivateObjectStorage {
    final byte[] bytes; int reads, signs;
    ObjectBytes(byte[] bytes) { this.bytes = bytes; }
    public InputStream open(String key) { assertEquals("objects/file", key); reads++; return new ByteArrayInputStream(bytes); }
    public String putPrivate(String key, String mime, long count, InputStream data) { throw new AssertionError("no new object"); }
    public StoredObject head(String key) { throw new AssertionError(); }
    public void delete(String key) { throw new AssertionError(); }
    public String signGet(String key, long expiry) { signs++; return "https://example.test/result"; }
  }
}
