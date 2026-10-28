package com.xzwl.cloud.storage.application.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreFileHandlerTest {
  private static final byte[] JPEG = bytes(0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4);
  private static final byte[] PNG = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1);
  private static final byte[] MP4 = bytes(0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
  private static final Instant CREATED_AT = Instant.parse("2026-09-07T08:00:00Z");
  private FakeObjectStorage objects;
  private MemoryMetadataStore metadata;
  private StoreFileHandler handler;

  @BeforeEach
  void setUp() {
    objects = new FakeObjectStorage();
    metadata = new MemoryMetadataStore();
    handler = new StoreFileHandler(objects, metadata, () -> "file-1",
        Clock.fixed(CREATED_AT, ZoneOffset.UTC), 100);
  }

  @Test
  void streamsValidatedReferenceAndAvatarImagesWithoutBase64Storage() {
    StoredFile reference = handler.handle(command(
        "owner-1", FilePurpose.REFERENCE_IMAGE, "image/jpeg", JPEG.length, JPEG));
    assertEquals("image/jpeg", reference.mimeType());
    assertEquals(JPEG.length, reference.sizeBytes());
    assertEquals("objects/file-1", reference.objectKey());
    assertEquals(reference, metadata.saved);
    assertEquals(JPEG.length, objects.uploaded.length);

    handler = new StoreFileHandler(objects, metadata, () -> "avatar-1",
        Clock.fixed(CREATED_AT, ZoneOffset.UTC), 100);
    StoredFile avatar = handler.handle(command(
        "owner-1", FilePurpose.AVATAR, "image/png", PNG.length, PNG));
    assertEquals("image/png", avatar.mimeType());
  }

  @Test void recoveryRejectsInvalidObjectMetadataWithoutDeletingOrReuploading() {
    String id = "archive-" + "a".repeat(64);
    String key = "objects/" + id;
    for (var invalid : java.util.List.of(
        new PrivateObjectStorage.StoredObject("objects/other", "video/mp4", 12, "etag"),
        new PrivateObjectStorage.StoredObject(key, "video/mp4", 0, "etag"),
        new PrivateObjectStorage.StoredObject(key, "video/mp4", 101, "etag"),
        new PrivateObjectStorage.StoredObject(key, "text/html", 12, "etag"),
        new PrivateObjectStorage.StoredObject(key, null, 12, "etag"),
        new PrivateObjectStorage.StoredObject(key, "video/mp4", 12, null),
        new PrivateObjectStorage.StoredObject(key, "video/mp4", 12, ""))) {
      objects.head = invalid;
      assertThrows(FileStorageException.class, () -> handler.recoverArchive("owner", id));
      assertFalse(objects.putCalled);
      assertEquals(0, objects.deleteCount);
      assertEquals(null, metadata.saved);
    }
    assertThrows(IllegalArgumentException.class, () -> handler.recoverArchive("owner", "file-1"));
  }

  @Test
  void rejectsOversizeFilesMismatchedContentTypesAndFakeSignaturesBeforeUpload() {
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", 20L * 1024 * 1024 + 1, JPEG)));
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "owner", FilePurpose.AVATAR, "image/png", 5L * 1024 * 1024 + 1, PNG)));
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/png", JPEG.length, JPEG)));
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", 4, bytes(1, 2, 3, 4))));
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "video/mp4", MP4.length, MP4)));
    assertFalse(objects.putCalled);
  }

  @Test
  void internalMediaAcceptsOnlyConfiguredSizeJpegPngAndMp4() {
    StoredFile stored = handler.handle(command(
        "media-owner", FilePurpose.MEDIA_RESULT, "video/mp4", MP4.length, MP4));
    assertEquals("video/mp4", stored.mimeType());

    handler = new StoreFileHandler(objects, metadata, () -> "too-large",
        Clock.fixed(CREATED_AT, ZoneOffset.UTC), MP4.length - 1);
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "media-owner", FilePurpose.MEDIA_RESULT, "video/mp4", MP4.length, MP4)));
    assertThrows(FileValidationException.class, () -> handler.handle(command(
        "media-owner", FilePurpose.MEDIA_RESULT, "video/webm", MP4.length, MP4)));
  }

  @Test
  void requiresMatchingKeyContentTypeAndLengthFromHeadBeforeSaving() {
    assertUnverified(new PrivateObjectStorage.StoredObject(
        "objects/other", "image/jpeg", JPEG.length, "etag"));
    assertUnverified(new PrivateObjectStorage.StoredObject(
        "objects/file-1", "image/png", JPEG.length, "etag"));
    assertUnverified(new PrivateObjectStorage.StoredObject(
        "objects/file-1", "image/jpeg", JPEG.length + 1, "etag"));
  }

  @Test
  void putFailureDoesNotSaveAndDatabaseFailureDeletesTheObject() {
    objects.failPut = true;
    assertThrows(FileStorageException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", JPEG.length, JPEG)));
    assertEquals(null, metadata.saved);
    assertEquals(0, objects.deleteCount);

    objects.failPut = false;
    metadata.failSave = true;
    assertThrows(FileStorageException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", JPEG.length, JPEG)));
    assertEquals("objects/file-1", objects.deletedKey);
    assertEquals(1, objects.deleteCount);
  }

  @Test
  void rejectsTrailingBytesWhenTheObjectClientReadsOnlyTheDeclaredLength() {
    objects.readOnlyDeclaredLength = true;
    byte[] content = bytes(0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5);

    assertThrows(FileStorageException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", JPEG.length, content)));

    assertEquals("objects/file-1", objects.deletedKey);
    assertEquals(null, metadata.saved);
  }

  @Test
  void genericBinaryMediaResponseUsesVerifiedSignatureButUploadMimeRemainsStrict() {
    var stored=handler.handle(command("owner",FilePurpose.MEDIA_RESULT,"application/octet-stream",PNG.length,PNG));
    assertEquals("image/png",stored.mimeType());
    assertEquals("image/png",objects.uploadedMime);
    assertThrows(FileValidationException.class,()->handler.handle(command("owner",FilePurpose.REFERENCE_IMAGE,"application/octet-stream",PNG.length,PNG)));
    assertThrows(FileValidationException.class,()->handler.handle(command("owner",FilePurpose.MEDIA_RESULT,"application/octet-stream",4,bytes(1,2,3,4))));
  }

  @Test void referenceLimitsAndDeclaredLengthAreCheckedBeforeObjectStorage() throws Exception {
    for (Object[] limit : new Object[][] {{"video/mp4", 200L * 1024 * 1024}, {"video/quicktime", 200L * 1024 * 1024},
        {"audio/wav", 15L * 1024 * 1024}, {"audio/mpeg", 15L * 1024 * 1024}, {"image/png", 20L * 1024 * 1024}}) {
      assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("owner", (String) limit[0],
          (Long) limit[1] + 1, new ByteArrayInputStream(MP4)));
    }
    java.util.Set<java.nio.file.Path> before = referenceTemporaryFiles();
    assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("owner", "image/png", PNG.length - 1,
        new ByteArrayInputStream(PNG)));
    assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("owner", "image/png", PNG.length + 1,
        new ByteArrayInputStream(PNG)));
    assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("owner", "video/mp4", MP4.length,
        new ByteArrayInputStream(MP4)));
    assertThrows(FileValidationException.class, () -> handler.handleReferenceMedia("owner", "video/mp4", 20, new InputStream() {
      @Override public int read() throws IOException { throw new IOException("interrupted upload"); }
    }));
    assertEquals(before, referenceTemporaryFiles());
    assertFalse(objects.putCalled);
    assertEquals(null, metadata.saved);
  }

  private static java.util.Set<java.nio.file.Path> referenceTemporaryFiles() throws IOException {
    try (var files = java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
      return files.filter(path -> path.getFileName().toString().startsWith("xzwl-reference-")).collect(java.util.stream.Collectors.toSet());
    }
  }

  private static StoreFileCommand command(
      String owner, FilePurpose purpose, String mime, long size, byte[] content) {
    return new StoreFileCommand(owner, purpose, mime, size, new ByteArrayInputStream(content));
  }

  private void assertUnverified(PrivateObjectStorage.StoredObject head) {
    objects = new FakeObjectStorage();
    metadata = new MemoryMetadataStore();
    handler = new StoreFileHandler(objects, metadata, () -> "file-1",
        Clock.fixed(CREATED_AT, ZoneOffset.UTC), 100);
    objects.head = head;
    assertThrows(FileStorageException.class, () -> handler.handle(command(
        "owner", FilePurpose.REFERENCE_IMAGE, "image/jpeg", JPEG.length, JPEG)));
    assertEquals("objects/file-1", objects.deletedKey);
    assertEquals(1, objects.deleteCount);
    assertEquals(null, metadata.saved);
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
    return result;
  }

  private static final class FakeObjectStorage implements PrivateObjectStorage {
    private boolean putCalled;
    private boolean failPut;
    private boolean readOnlyDeclaredLength;
    private byte[] uploaded;
    private String uploadedKey;
    private String uploadedMime;
    private StoredObject head;
    private String deletedKey;
    private int deleteCount;

    @Override
    public String putPrivate(String objectKey, String mimeType, long contentLength, InputStream body) {
      putCalled = true;
      if (failPut) throw new FileStorageException("put failed");
      uploadedKey = objectKey;
      uploadedMime = mimeType;
      try {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        if (readOnlyDeclaredLength) {
          long remaining = contentLength;
          byte[] buffer = new byte[4];
          while (remaining > 0) {
            int read = body.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (read < 0) break;
            sink.write(buffer, 0, read);
            remaining -= read;
          }
        } else {
          body.transferTo(sink);
        }
        uploaded = sink.toByteArray();
        return "put-etag";
      } catch (IOException failed) {
        throw new FileStorageException("read failed", failed);
      }
    }

    @Override
    public StoredObject head(String objectKey) {
      return head == null
          ? new StoredObject(uploadedKey, uploadedMime, uploaded.length, "head-etag")
          : head;
    }

    @Override
    public void delete(String objectKey) {
      deletedKey = objectKey;
      deleteCount++;
    }

    @Override
    public String signGet(String objectKey, long expiresSeconds) {
      return "https://signed.example/" + objectKey + "?expires=" + expiresSeconds;
    }
  }

  private static final class MemoryMetadataStore implements FileMetadataStore {
    private StoredFile saved;
    private boolean failSave;

    @Override
    public void save(StoredFile file) {
      if (failSave) throw new FileStorageException("database failed");
      saved = file;
    }

    @Override
    public Optional<StoredFile> findById(String fileId) {
      return Optional.ofNullable(saved).filter(file -> file.fileId().equals(fileId));
    }
  }
}
