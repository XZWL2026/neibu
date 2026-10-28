package com.xzwl.cloud.storage.application.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreateAccessUrlHandlerTest {
  private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
  private static final StoredFile FILE = new StoredFile(
      "file-1", "owner-1", "objects/file-1", "image/png", 9, "etag",
      FilePurpose.REFERENCE_IMAGE, NOW);

  @Test
  void ownerReceivesAUrlValidForExactlyOneHour() {
    RecordingStorage objects = new RecordingStorage();
    CreateAccessUrlResult result = handler(objects).handle("file-1", "owner-1");

    assertEquals(3600, objects.expiresSeconds);
    assertEquals("https://signed.example/object", result.url());
    assertEquals(NOW.plusSeconds(3600), result.expiresAt());
  }

  @Test
  void anotherOwnerCannotReceiveASignedUrl() {
    RecordingStorage objects = new RecordingStorage();
    assertThrows(FileAccessDeniedException.class,
        () -> handler(objects).handle("file-1", "owner-2"));
    assertEquals(0, objects.expiresSeconds);
  }

  @Test void purposeConstraintRunsBeforeSigning() {
    var objects = new RecordingStorage();
    assertThrows(FileAccessDeniedException.class,
        () -> handler(objects).handle("file-1", "owner-1", FilePurpose.AVATAR));
    assertEquals(0, objects.expiresSeconds);
    assertEquals(NOW.plusSeconds(3600),
        handler(objects).handle("file-1", "owner-1", FilePurpose.REFERENCE_IMAGE).expiresAt());
  }

  @Test void mediaReferencesWithIncompleteMetadataRequireReuploadBeforeSigning() {
    for (var file : java.util.List.of(
        new StoredFile("file-1", "owner-1", "objects/file-1", "video/mp4", 200, "etag", FilePurpose.REFERENCE_VIDEO, NOW),
        new StoredFile("file-1", "owner-1", "objects/file-1", "audio/wav", 200, "etag", FilePurpose.REFERENCE_AUDIO, NOW),
        new StoredFile("file-1", "owner-1", "objects/file-1", "video/mp4", 200, "etag", FilePurpose.REFERENCE_VIDEO, NOW, 3.0, null, null, null))) {
      var objects = new RecordingStorage();
      FileMetadataStore metadata = new FileMetadataStore() {
        public void save(StoredFile value) {}
        public Optional<StoredFile> findById(String id) { return Optional.of(file); }
      };
      var handler = new CreateAccessUrlHandler(metadata, objects, Clock.fixed(NOW, ZoneOffset.UTC));
      assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("file-1", "owner-1"));
      assertEquals(0, objects.expiresSeconds);
    }
  }

  @Test void omniChecksPersistedImageLimitsBeforeSigningIncludingGeneratedResults() {
    for (var dimensions : java.util.List.of(new int[] {299, 600}, new int[] {6001, 600},
        new int[] {600, 299}, new int[] {600, 6001}, new int[] {300, 1000}, new int[] {1000, 300}, new int[] {600, 600})) {
      var objects = new RecordingStorage();
      var file = new StoredFile("file-1", "owner-1", "objects/file-1", "image/png",
          dimensions[0] == 600 && dimensions[1] == 600 ? 20L * 1024 * 1024 + 1 : 1000,
          "etag", FilePurpose.MEDIA_RESULT, NOW, null, dimensions[0], dimensions[1], null);
      FileMetadataStore metadata = new FileMetadataStore() {
        public void save(StoredFile value) {}
        public Optional<StoredFile> findById(String id) { return Optional.of(file); }
      };
      var handler = new CreateAccessUrlHandler(metadata, objects, Clock.fixed(NOW, ZoneOffset.UTC));
      assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("file-1", "owner-1", true));
      assertEquals(0, objects.expiresSeconds);
      assertEquals("image/png", handler.handleReferenceMedia("file-1", "owner-1", false).mimeType());
    }
  }

  @Test void imageMatchingBoundsReadsAndClosesOriginalWithoutWriting() throws Exception {
    var objects = new RecordingStorage();
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("reference".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(true, handler(objects).matchesImage("file-1", "owner-1", 9, digest));
    assertEquals(1, objects.opens); assertEquals(1, objects.closes);
    assertEquals(false, handler(objects).matchesImage("file-1", "owner-1", 8, digest));
    assertEquals(1, objects.opens);
    assertThrows(FileAccessDeniedException.class, () -> handler(objects).matchesImage("file-1", "owner-2", 9, digest));
    assertThrows(FileValidationException.class, () -> handler(objects).matchesImage("file-1", "owner-1", 20L * 1024 * 1024 + 1, digest));
    assertThrows(FileValidationException.class, () -> handler(objects).matchesImage("file-1", "owner-1", 9, null));
    assertEquals(1, objects.opens);
    for (String corrupted : java.util.List.of("short", "reference-long")) {
      objects.content = corrupted;
      assertThrows(FileStorageException.class, () -> handler(objects).matchesImage("file-1", "owner-1", 9, digest));
    }
    assertEquals(3, objects.opens); assertEquals(3, objects.closes);
    assertEquals(0, objects.expiresSeconds);
  }

  @Test void imageContentReturnsOriginalBytesAndClosesTheOwnedObject() {
    for(var purpose:java.util.List.of(FilePurpose.REFERENCE_IMAGE,FilePurpose.MEDIA_RESULT)) {
      var objects=new RecordingStorage();
      var file=new StoredFile("file-1","owner-1","objects/file-1","image/png",9,"etag",purpose,NOW);
      var result=handler(objects,file).imageContent("file-1","owner-1");
      assertEquals("image/png",result.mimeType());
      assertEquals("cmVmZXJlbmNl",result.base64());
      assertEquals(1,objects.opens);assertEquals(1,objects.closes);assertEquals(0,objects.expiresSeconds);
    }
  }

  @Test void imageContentRejectsForeignMissingWrongPurposeWrongMimeAndOversizedBeforeOpening() {
    var objects=new RecordingStorage();
    assertThrows(FileAccessDeniedException.class,()->handler(objects).imageContent("file-1","other"));
    assertThrows(StoredFileNotFoundException.class,()->handler(objects).imageContent("missing","owner-1"));
    for(var file:java.util.List.of(
        new StoredFile("file-1","owner-1","objects/file-1","image/png",9,"etag",FilePurpose.AVATAR,NOW),
        new StoredFile("file-1","owner-1","objects/file-1","video/mp4",9,"etag",FilePurpose.MEDIA_RESULT,NOW)))
      assertThrows(FileAccessDeniedException.class,()->handler(objects,file).imageContent("file-1","owner-1"));
    var oversized=new StoredFile("file-1","owner-1","objects/file-1","image/png",20L*1024*1024+1,"etag",FilePurpose.MEDIA_RESULT,NOW);
    assertThrows(FileValidationException.class,()->handler(objects,oversized).imageContent("file-1","owner-1"));
    assertEquals(0,objects.opens);
  }

  @Test void imageContentAcceptsTheExactLimitAndClosesMismatchedOrFailedStreams() {
    var objects=new RecordingStorage();
    objects.content="x".repeat(20*1024*1024);
    var maximum=new StoredFile("file-1","owner-1","objects/file-1","image/png",objects.content.length(),"etag",FilePurpose.MEDIA_RESULT,NOW);
    assertEquals(objects.content.length(),java.util.Base64.getDecoder().decode(handler(objects,maximum).imageContent("file-1","owner-1").base64()).length);
    for(String corrupt:java.util.List.of("short","reference-long")) {
      objects.content=corrupt;
      assertThrows(FileStorageException.class,()->handler(objects).imageContent("file-1","owner-1"));
    }
    objects.failRead=true;
    assertThrows(FileStorageException.class,()->handler(objects).imageContent("file-1","owner-1"));
    assertEquals(4,objects.opens);assertEquals(4,objects.closes);
  }

  @Test void videoContentRestrictsOwnershipPurposeMimeAndSizeAndClosesStream() {
    var objects=new RecordingStorage();
    var video=new StoredFile("file-1","owner-1","objects/file-1","video/mp4",9,"etag",FilePurpose.REFERENCE_VIDEO,NOW);
    assertThrows(FileAccessDeniedException.class,()->handler(objects,video).videoContent("file-1","other"));
    assertThrows(FileAccessDeniedException.class,()->handler(objects).videoContent("file-1","owner-1"));
    var oversized=new StoredFile("file-1","owner-1","objects/file-1","video/mp4",20L*1024*1024+1,"etag",FilePurpose.REFERENCE_VIDEO,NOW);
    assertThrows(FileValidationException.class,()->handler(objects,oversized).videoContent("file-1","owner-1"));
    assertEquals(0,objects.opens);
    assertEquals("cmVmZXJlbmNl",handler(objects,video).videoContent("file-1","owner-1").base64());
    assertEquals(1,objects.closes);
    objects.content="short";
    assertThrows(FileStorageException.class,()->handler(objects,video).videoContent("file-1","owner-1"));
    assertEquals(2,objects.closes);
  }

  private static CreateAccessUrlHandler handler(RecordingStorage objects) {
    return handler(objects,FILE);
  }
  private static CreateAccessUrlHandler handler(RecordingStorage objects,StoredFile stored) {
    FileMetadataStore metadata = new FileMetadataStore() {
      public void save(StoredFile file) {}
      public Optional<StoredFile> findById(String fileId) { return fileId.equals(stored.fileId())?Optional.of(stored):Optional.empty(); }
    };
    return new CreateAccessUrlHandler(metadata, objects, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class RecordingStorage implements PrivateObjectStorage {
    private long expiresSeconds;
    private int opens, closes;
    private String content = "reference";
    private boolean failRead;
    @Override public InputStream open(String key) {
      opens++;
      if(failRead) return new InputStream() {
        @Override public int read() throws java.io.IOException { throw new java.io.IOException("read failed"); }
        @Override public void close() { closes++; }
      };
      return new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
        @Override public void close() { closes++; }
      };
    }
    public String putPrivate(String key, String mime, long size, InputStream body) { return ""; }
    public StoredObject head(String key) { throw new UnsupportedOperationException(); }
    public void delete(String key) {}
    public String signGet(String key, long seconds) {
      expiresSeconds = seconds;
      return "https://signed.example/object";
    }
  }
}
