package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.StoredFile;
import com.xzwl.cloud.storage.domain.file.FilePurpose;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class CreateAccessUrlHandler {
  public static final long EXPIRES_SECONDS = 3600;

  private final FileMetadataStore metadata;
  private final PrivateObjectStorage objects;
  private final Clock clock;

  public CreateAccessUrlHandler(
      FileMetadataStore metadata, PrivateObjectStorage objects, Clock clock) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.objects = Objects.requireNonNull(objects, "objects");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CreateAccessUrlResult handle(String fileId, String ownerId) {
    return handle(fileId, ownerId, null);
  }

  public CreateAccessUrlResult handle(String fileId, String ownerId, FilePurpose expectedPurpose) {
    StoredFile file = authorized(fileId, ownerId, expectedPurpose);
    return new CreateAccessUrlResult(
        objects.signGet(file.objectKey(), EXPIRES_SECONDS),
        Instant.now(clock).plusSeconds(EXPIRES_SECONDS));
  }
  public record ImageContent(String mimeType, String base64) {}

  public ImageContent imageContent(String fileId, String ownerId) {
    StoredFile file = authorized(fileId, ownerId, null);
    if (!file.mimeType().startsWith("image/")
        || (file.purpose() != FilePurpose.REFERENCE_IMAGE && file.purpose() != FilePurpose.MEDIA_RESULT))
      throw new FileAccessDeniedException();
    return new ImageContent(file.mimeType(), readContent(file));
  }

  public record VideoContent(String mimeType, String base64) {}
  public VideoContent videoContent(String fileId, String ownerId) {
    StoredFile file = authorized(fileId, ownerId, null);
    if (!file.mimeType().startsWith("video/")
        || (file.purpose() != FilePurpose.REFERENCE_VIDEO && file.purpose() != FilePurpose.MEDIA_RESULT
            && file.purpose() != FilePurpose.PROCESSING_VIDEO)) throw new FileAccessDeniedException();
    return new VideoContent(file.mimeType(), readContent(file));
  }

  private String readContent(StoredFile file) {
    if (file.sizeBytes() > 20L * 1024 * 1024)
      throw new FileValidationException("file exceeds 20 MB");
    try (var input = objects.open(file.objectKey())) {
      byte[] bytes = input.readNBytes((int) file.sizeBytes() + 1);
      if (bytes.length != file.sizeBytes())
        throw new FileStorageException("original file length differs from metadata");
      return java.util.Base64.getEncoder().encodeToString(bytes);
    } catch (java.io.IOException failure) {
      throw new FileStorageException("original file could not be read", failure);
    }
  }

  /** Match a legacy local image to an owned original without uploading or changing the object. */
  public boolean matchesImage(String fileId, String ownerId, long sizeBytes, String sha256) {
    StoredFile file = authorized(fileId, ownerId, null);
    if (!file.mimeType().startsWith("image/")
        || (file.purpose() != FilePurpose.REFERENCE_IMAGE && file.purpose() != FilePurpose.MEDIA_RESULT))
      throw new FileAccessDeniedException();
    if (sizeBytes <= 0 || sizeBytes > 20L * 1024 * 1024 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new FileValidationException("invalid image fingerprint");
    if (file.sizeBytes() != sizeBytes) return false;
    try (var input = objects.open(file.objectKey())) {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192]; long copied = 0; int count;
      while ((count = input.read(buffer)) != -1) {
        copied += count;
        if (copied > sizeBytes) throw new FileStorageException("original image length differs from metadata");
        digest.update(buffer, 0, count);
      }
      if (copied != sizeBytes) throw new FileStorageException("original image length differs from metadata");
      return java.security.MessageDigest.isEqual(digest.digest(), java.util.HexFormat.of().parseHex(sha256));
    } catch (java.io.IOException failure) {
      throw new FileStorageException("original image could not be read", failure);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public ReferenceAccessUrlResult handleReferenceMedia(String fileId, String ownerId) {
    return handleReferenceMedia(fileId, ownerId, false);
  }

  public ReferenceAccessUrlResult handleReferenceMedia(String fileId, String ownerId, boolean omni) {
    StoredFile file = authorized(fileId, ownerId, null);
    if (file.purpose() != FilePurpose.REFERENCE_IMAGE && file.purpose() != FilePurpose.REFERENCE_VIDEO
        && file.purpose() != FilePurpose.REFERENCE_AUDIO && file.purpose() != FilePurpose.MEDIA_RESULT && file.purpose()!=FilePurpose.PROCESSING_VIDEO)
      throw new FileAccessDeniedException();
    boolean missing = file.mimeType().startsWith("image/")
        ? omni && (file.width() == null || file.height() == null)
        : file.durationSeconds() == null || file.width() == null || file.height() == null || file.fps() == null;
    if (missing && (file.purpose() == FilePurpose.MEDIA_RESULT || file.purpose() == FilePurpose.REFERENCE_IMAGE)) {
      file = inspectStoredMedia(file);
    }
    if ((!file.mimeType().startsWith("image/") && file.durationSeconds() == null)
        || (file.mimeType().startsWith("video/")
            && (file.width() == null || file.height() == null || file.fps() == null)))
      throw new FileValidationException("reference media metadata is missing; upload the file again");
    if(file.mimeType().startsWith("video/")) {
      double ratio=(double)file.width()/file.height(); long pixels=(long)file.width()*file.height();
      if(file.durationSeconds()<2 || file.durationSeconds()>30 || file.fps()<24 || file.fps()>60
          || file.width()<300 || file.height()<300 || file.width()>6000 || file.height()>6000
          || pixels<407696 || pixels>8295044 || ratio<0.4 || ratio>2.5) throw new FileValidationException("video is outside reference limits");
    }
    if (omni && file.mimeType().startsWith("image/")) {
      if (file.width() == null || file.height() == null || file.width() < 300 || file.width() > 6000
          || file.height() < 300 || file.height() > 6000 || file.sizeBytes() > 20L * 1024 * 1024
          || (double) file.width() / file.height() < 0.4 || (double) file.width() / file.height() > 2.5) {
        throw new FileValidationException("reference image metadata unavailable or outside allowed limits; upload again");
      }
    }
    return new ReferenceAccessUrlResult(objects.signGet(file.objectKey(), EXPIRES_SECONDS),
        Instant.now(clock).plusSeconds(EXPIRES_SECONDS), file.mimeType(), file.sizeBytes(),
        file.durationSeconds(), file.width(), file.height(), file.fps());
  }

  // Legacy uploads and generated results keep their original ID and object. Inspect private bytes
  // only after ownership authorization, then reuse the verified metadata on subsequent requests.
  public ReferenceAccessUrlResult handleProcessingVideo(String fileId,String ownerId) {
    StoredFile file=authorized(fileId,ownerId,null);
    if(!file.mimeType().startsWith("video/") || (file.purpose()!=FilePurpose.PROCESSING_VIDEO && file.purpose()!=FilePurpose.REFERENCE_VIDEO && file.purpose()!=FilePurpose.MEDIA_RESULT)) throw new FileAccessDeniedException();
    if(file.purpose()!=FilePurpose.PROCESSING_VIDEO || file.durationSeconds()==null || file.width()==null || file.height()==null || file.fps()==null) file=inspectStoredMedia(file,true);
    return new ReferenceAccessUrlResult(objects.signGet(file.objectKey(),EXPIRES_SECONDS),Instant.now(clock).plusSeconds(EXPIRES_SECONDS),file.mimeType(),file.sizeBytes(),file.durationSeconds(),file.width(),file.height(),file.fps());
  }
  private StoredFile inspectStoredMedia(StoredFile file) { return inspectStoredMedia(file,false); }
  private StoredFile inspectStoredMedia(StoredFile file,boolean processing) {
    long maximum = ReferenceMediaProbe.maxBytes(file.mimeType());
    if (file.sizeBytes() <= 0 || file.sizeBytes() > maximum)
      throw new FileValidationException("reference media size is outside the allowed range");
    java.nio.file.Path temporary = null;
    try {
      temporary = java.nio.file.Files.createTempFile("xzwl-stored-reference-", ".media");
      try (var input = objects.open(file.objectKey()); var output = java.nio.file.Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[8192]; long copied = 0; int count;
        while ((count = input.read(buffer)) != -1) {
          copied += count;
          if (copied > file.sizeBytes()) throw new FileValidationException("reference object length differs from metadata");
          output.write(buffer, 0, count);
        }
        if (copied != file.sizeBytes()) throw new FileValidationException("reference object length differs from metadata");
      }
      var inspected = ReferenceMediaProbe.inspect(temporary, file.mimeType(),processing);
      var updated = new StoredFile(file.fileId(), file.ownerId(), file.objectKey(), file.mimeType(),
          file.sizeBytes(), file.etag(), file.purpose(), file.createdAt(),
          inspected.durationSeconds(), inspected.width(), inspected.height(), inspected.fps());
      metadata.updateMediaMetadata(updated);
      return updated;
    } catch (java.io.IOException failure) {
      throw new FileValidationException("reference media could not be inspected", failure);
    } finally {
      if (temporary != null) {
        try { java.nio.file.Files.deleteIfExists(temporary); }
        catch (java.io.IOException failure) { throw new FileStorageException("reference temporary file could not be removed", failure); }
      }
    }
  }

  public record ReferenceAccessUrlResult(String url, Instant expiresAt, String mimeType, long sizeBytes,
      Double durationSeconds, Integer width, Integer height, Double fps) {}

  /** Administrator task access verifies the object still exists before issuing a copyable URL. */
  public CreateAccessUrlResult handleExisting(String fileId,String ownerId) {
    StoredFile file=authorized(fileId,ownerId,null);
    var object=objects.head(file.objectKey());
    if(object==null || object.contentLength()<=0) throw new FileStorageException("File is missing or unreadable");
    return new CreateAccessUrlResult(objects.signGet(file.objectKey(),EXPIRES_SECONDS),
        Instant.now(clock).plusSeconds(EXPIRES_SECONDS));
  }
  private StoredFile authorized(String fileId,String ownerId,FilePurpose expectedPurpose) {
    StoredFile file = metadata.findById(fileId)
        .orElseThrow(() -> new StoredFileNotFoundException(fileId));
    if (!file.ownerId().equals(ownerId)) throw new FileAccessDeniedException();
    if (expectedPurpose != null && file.purpose() != expectedPurpose) throw new FileAccessDeniedException();
    return file;
  }
}
