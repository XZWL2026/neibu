package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class StoreFileHandler {
  private static final long REFERENCE_MAX_BYTES = 20L * 1024 * 1024;
  private static final long AVATAR_MAX_BYTES = 5L * 1024 * 1024;

  private final PrivateObjectStorage objects;
  private final FileMetadataStore metadata;
  private final Supplier<String> idGenerator;
  private final Clock clock;
  private final long mediaResultMaxBytes;

  public StoreFileHandler(
      PrivateObjectStorage objects,
      FileMetadataStore metadata,
      Supplier<String> idGenerator,
      Clock clock,
      long mediaResultMaxBytes) {
    this.objects = Objects.requireNonNull(objects, "objects");
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (mediaResultMaxBytes <= 0) {
      throw new IllegalArgumentException("mediaResultMaxBytes must be positive");
    }
    this.mediaResultMaxBytes = mediaResultMaxBytes;
  }

  public StoredFile handleReferenceMedia(String ownerId, String mimeType, long sizeBytes, InputStream body) {
    return handleMedia(ownerId,mimeType,sizeBytes,body,false);
  }
  public StoredFile handleProcessingVideo(String ownerId,String mimeType,long sizeBytes,InputStream body) {
    return handleMedia(ownerId,mimeType,sizeBytes,body,true);
  }
  /** Create a bounded reference from an owned original; never replace its bytes or metadata. */
  public StoredFile clipReferenceVideo(String ownerId, String fileId, int maxDurationSeconds) {
    requireNonBlank(ownerId,"ownerId");
    var file=metadata.findById(fileId).orElseThrow(()->new StoredFileNotFoundException(fileId));
    if(!file.ownerId().equals(ownerId)) throw new FileAccessDeniedException();
    if(!file.mimeType().startsWith("video/") || (file.purpose()!=FilePurpose.PROCESSING_VIDEO
        && file.purpose()!=FilePurpose.REFERENCE_VIDEO && file.purpose()!=FilePurpose.MEDIA_RESULT)) throw new FileAccessDeniedException();
    if(maxDurationSeconds<2 || maxDurationSeconds>30 || file.sizeBytes()>ReferenceMediaProbe.VIDEO_MAX_BYTES)
      throw new FileValidationException("reference clip duration must be 2–30 seconds");
    java.nio.file.Path source=null, clip=null;
    Process process=null;
    try {
      source=java.nio.file.Files.createTempFile("xzwl-clip-source-",".mp4");
      clip=java.nio.file.Files.createTempFile("xzwl-clip-",".mp4");
      try(var input=objects.open(file.objectKey()); var output=java.nio.file.Files.newOutputStream(source)) {
        byte[] buffer=new byte[8192]; long size=0; int read;
        while((read=input.read(buffer))!=-1) {
          size+=read;
          if(size>file.sizeBytes()) throw new FileStorageException("original file length differs from metadata");
          output.write(buffer,0,read);
        }
        if(size!=file.sizeBytes()) throw new FileStorageException("original file length differs from metadata");
      }
      var original=ReferenceMediaProbe.inspect(source,file.mimeType(),true);
      if(original.durationSeconds()<2 || original.durationSeconds()>120)
        throw new FileValidationException("replica video duration must be 2–120 seconds");
      // Use filter-time dimensions after FFmpeg autorotation; preserve the original display ratio.
      String scale="max(1,sqrt(407696/(iw*ih)))";
      String filter="scale='ceil(iw*"+scale+"/2)*2':'ceil(ih*"+scale+"/2)*2',fps=24";
      String executable=System.getenv().getOrDefault("STORAGE_FFMPEG_PATH","ffmpeg");
      process=new ProcessBuilder(executable,"-nostdin","-v","error","-y","-protocol_whitelist","file",
          "-format_whitelist","mov","-threads","2","-i",source.toString(),"-map","0:v:0","-map","0:a:0?",
          "-t",Integer.toString(maxDurationSeconds),"-vf",filter,"-c:v","libx264","-preset","ultrafast",
          "-threads","2","-pix_fmt","yuv420p","-af","atrim=duration="+(maxDurationSeconds-0.1),"-c:a","aac",
          "-map_metadata","-1","-movflags","+faststart",clip.toString())
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      process.getOutputStream().close();
      if(!process.waitFor(40,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue()!=0)
        throw new FileStorageException("reference video clip could not be prepared");
      var inspected=ReferenceMediaProbe.inspect(clip,"video/mp4");
      long size=java.nio.file.Files.size(clip);
      if(inspected.durationSeconds()>maxDurationSeconds || size>ReferenceMediaProbe.VIDEO_MAX_BYTES)
        throw new FileValidationException("reference clip exceeds the requested limits");
      try(var input=java.nio.file.Files.newInputStream(clip)) {
        return store(new StoreFileCommand(ownerId,FilePurpose.REFERENCE_VIDEO,"video/mp4",size,input),inspected);
      }
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new FileStorageException("reference clip interrupted",interrupted);
    } catch(IOException failure) { throw new FileStorageException("reference clip could not be prepared",failure); }
    finally {
      if(process!=null && process.isAlive()) {
        process.destroyForcibly();
        try {process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
      }
      for(var temporary:new java.nio.file.Path[]{source,clip}) if(temporary!=null) {
        try {java.nio.file.Files.deleteIfExists(temporary);} catch(IOException failure) {throw new FileStorageException("clip temporary file could not be removed",failure);}
      }
    }
  }

  private StoredFile handleMedia(String ownerId,String mimeType,long sizeBytes,InputStream body,boolean processing) {
    requireNonBlank(ownerId, "ownerId");
    Objects.requireNonNull(body, "body");
    long maxBytes = ReferenceMediaProbe.maxBytes(mimeType);
    if (sizeBytes <= 0 || sizeBytes > maxBytes) throw new FileValidationException("file size is outside the allowed range");
    java.nio.file.Path temporary = null;
    try {
      temporary = java.nio.file.Files.createTempFile("xzwl-reference-", ".media");
      try (var output = java.nio.file.Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[8192];
        long copied = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
          copied += read;
          if (copied > sizeBytes || copied > maxBytes) throw new FileValidationException("file exceeds its declared size");
          output.write(buffer, 0, read);
        }
        if (copied != sizeBytes) throw new FileValidationException("file size differs from the declared size");
      }
      var inspected = ReferenceMediaProbe.inspect(temporary, mimeType,processing);
      try (var input = java.nio.file.Files.newInputStream(temporary)) {
        return store(new StoreFileCommand(ownerId, inspected.purpose(), inspected.mimeType(), sizeBytes, input), inspected);
      }
    } catch (IOException failure) {
      throw new FileValidationException("reference media could not be read or inspected", failure);
    } finally {
      if (temporary != null) {
        try { java.nio.file.Files.deleteIfExists(temporary); }
        catch (IOException failure) { throw new FileStorageException("reference temporary file could not be removed", failure); }
      }
    }
  }

  public StoredFile handle(StoreFileCommand command) {
    if (command.purpose() == FilePurpose.PROCESSING_VIDEO || command.purpose() == FilePurpose.REFERENCE_VIDEO || command.purpose() == FilePurpose.REFERENCE_AUDIO) {
      throw new FileValidationException("audio and video references require media inspection");
    }
    return store(command, null);
  }

  public StoredFile handleArchive(StoreFileCommand command, String fileId) {
    if (command.purpose() != FilePurpose.MEDIA_RESULT)
      throw new FileValidationException("archive must be a media result");
    return store(command, null, requireArchiveId(fileId));
  }

  public java.util.Optional<StoredFile> recoverArchive(String ownerId, String fileId) {
    requireNonBlank(ownerId, "ownerId");
    String key = "objects/" + requireArchiveId(fileId);
    return objects.find(key).map(object -> {
      if (!key.equals(object.objectKey()) || object.contentLength() <= 0
          || object.contentLength() > mediaResultMaxBytes
          || object.contentType() == null
          || !java.util.Set.of("image/jpeg", "image/png", "video/mp4").contains(object.contentType())
          || object.etag() == null || object.etag().isBlank())
        throw new FileStorageException("archived object verification failed");
      var file = new StoredFile(fileId, ownerId, key, object.contentType(), object.contentLength(),
          object.etag(), FilePurpose.MEDIA_RESULT, Instant.now(clock));
      metadata.save(file);
      return file;
    });
  }

  private static String requireArchiveId(String fileId) {
    if (fileId == null || !fileId.matches("archive-[0-9a-f]{64}"))
      throw new IllegalArgumentException("invalid archive fileId");
    return fileId;
  }

  private StoredFile store(StoreFileCommand command, ReferenceMediaProbe.Metadata inspected) {
    return store(command, inspected, null);
  }

  private StoredFile store(StoreFileCommand command, ReferenceMediaProbe.Metadata inspected, String archiveFileId) {
    StoreFileCommand requested = Objects.requireNonNull(command, "command");
    requireNonBlank(requested.ownerId(), "ownerId");
    Objects.requireNonNull(requested.purpose(), "purpose");
    Objects.requireNonNull(requested.body(), "body");
    long maxBytes = maxBytes(requested.purpose());
    if (requested.declaredSizeBytes() <= 0 || requested.declaredSizeBytes() > maxBytes) {
      throw new FileValidationException("file size is outside the allowed range");
    }

    String fileId = archiveFileId == null ? requireFileId(idGenerator.get()) : archiveFileId;
    String objectKey = "objects/" + fileId;
    LimitedInputStream content;
    String actualMime;
    try {
      byte[] signature = requested.body().readNBytes(12);
      actualMime = inspected == null ? detectMime(signature) : inspected.mimeType();
      content = new LimitedInputStream(
          new SequenceInputStream(new ByteArrayInputStream(signature), requested.body()), maxBytes);
    } catch (IOException failed) {
      throw new FileValidationException("cannot inspect file signature", failed);
    }
    if (inspected == null) validateMime(requested.purpose(), requested.declaredMimeType(), actualMime);

    boolean objectWritten = false;
    boolean metadataWriteStarted = false;
    try {
      objects.putPrivate(objectKey, actualMime, requested.declaredSizeBytes(), content);
      objectWritten = true;
      long streamedBytes = verifiedStreamLength(content, requested.declaredSizeBytes());
      PrivateObjectStorage.StoredObject stored = objects.head(objectKey);
      if (!objectKey.equals(stored.objectKey())
          || !actualMime.equals(stored.contentType())
          || streamedBytes != stored.contentLength()) {
        throw new FileStorageException("TOS HEAD verification failed");
      }
      StoredFile file = new StoredFile(
          fileId, requested.ownerId(), objectKey, actualMime, streamedBytes, stored.etag(),
          requested.purpose(), Instant.now(clock),
          inspected == null ? null : inspected.durationSeconds(),
          inspected == null ? null : inspected.width(),
          inspected == null ? null : inspected.height(),
          inspected == null ? null : inspected.fps());
      metadataWriteStarted = true;
      metadata.save(file);
      return file;
    } catch (RuntimeException failure) {
      // A verified stable archive can be recovered after a metadata write/commit failure.
      if (objectWritten && (archiveFileId == null || !metadataWriteStarted)) cleanup(objectKey, failure);
      if (failure instanceof FileStorageException fileStorageFailure) {
        throw fileStorageFailure;
      }
      throw metadataWriteStarted
          ? new FileStorageException("file metadata could not be saved", failure)
          : failure instanceof FileValidationException validationFailure
              ? validationFailure
              : new FileStorageException("private object storage failed", failure);
    }
  }

  private void cleanup(String objectKey, RuntimeException failure) {
    try {
      objects.delete(objectKey);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private static long verifiedStreamLength(LimitedInputStream content, long declaredSizeBytes) {
    if (content.count() != declaredSizeBytes) {
      throw new FileStorageException("streamed length differs from declared length");
    }
    try {
      if (content.read() >= 0) {
        throw new FileStorageException("stream contains bytes beyond the declared length");
      }
    } catch (IOException failed) {
      throw new FileStorageException("stream length could not be verified", failed);
    }
    return content.count();
  }

  private long maxBytes(FilePurpose purpose) {
    return switch (purpose) {
      case REFERENCE_IMAGE -> REFERENCE_MAX_BYTES;
      case PROCESSING_VIDEO, REFERENCE_VIDEO -> ReferenceMediaProbe.VIDEO_MAX_BYTES;
      case REFERENCE_AUDIO -> ReferenceMediaProbe.AUDIO_MAX_BYTES;
      case AVATAR -> AVATAR_MAX_BYTES;
      case MEDIA_RESULT -> mediaResultMaxBytes;
    };
  }

  private static void validateMime(FilePurpose purpose, String declaredMime, String actualMime) {
    if (!actualMime.equals(declaredMime)
        && !(purpose == FilePurpose.MEDIA_RESULT && "application/octet-stream".equals(declaredMime))) {
      throw new FileValidationException("declared MIME does not match file signature");
    }
    boolean image = actualMime.equals("image/jpeg") || actualMime.equals("image/png");
    if (!image && !(purpose == FilePurpose.MEDIA_RESULT && actualMime.equals("video/mp4"))) {
      throw new FileValidationException("MIME is not allowed for this upload purpose");
    }
  }

  private static String detectMime(byte[] signature) {
    if (signature.length >= 3
        && unsigned(signature[0]) == 0xff
        && unsigned(signature[1]) == 0xd8
        && unsigned(signature[2]) == 0xff) {
      return "image/jpeg";
    }
    int[] png = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    if (signature.length >= png.length) {
      boolean matches = true;
      for (int index = 0; index < png.length; index++) {
        matches &= unsigned(signature[index]) == png[index];
      }
      if (matches) return "image/png";
    }
    if (signature.length >= 8
        && signature[4] == 'f' && signature[5] == 't'
        && signature[6] == 'y' && signature[7] == 'p') {
      return "video/mp4";
    }
    throw new FileValidationException("unsupported file signature");
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new FileValidationException(name + " must not be blank");
    }
    return value;
  }

  private static String requireFileId(String fileId) {
    if (fileId == null || !fileId.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalStateException("generated fileId is invalid");
    }
    return fileId;
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private final long maxBytes;
    private long count;

    private LimitedInputStream(InputStream input, long maxBytes) {
      super(input);
      this.maxBytes = maxBytes;
    }

    long count() {
      return count;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) increment(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0) increment(read);
      return read;
    }

    private void increment(int bytes) throws IOException {
      count += bytes;
      if (count > maxBytes) throw new IOException("file exceeds configured maximum");
    }
  }
}
