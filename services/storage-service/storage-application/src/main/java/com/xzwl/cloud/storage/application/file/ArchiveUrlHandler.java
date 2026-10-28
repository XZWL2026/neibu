package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.net.URI;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class ArchiveUrlHandler {
  private final RemoteFileSource remoteFiles;
  private final StoreFileHandler storeFile;
  private final BiFunction<String, Function<String, StoredFile>, StoredFile> archiveOnce;

  public ArchiveUrlHandler(RemoteFileSource remoteFiles, StoreFileHandler storeFile,
      BiFunction<String, Function<String, StoredFile>, StoredFile> archiveOnce) {
    this.remoteFiles = Objects.requireNonNull(remoteFiles, "remoteFiles");
    this.storeFile = Objects.requireNonNull(storeFile, "storeFile");
    this.archiveOnce = Objects.requireNonNull(archiveOnce, "archiveOnce");
  }

  public StoredFile handle(String ownerId, String sourceUrl) {
    return handle(ownerId, sourceUrl, null);
  }

  public StoredFile handle(String ownerId, String sourceUrl, String archiveKey) {
    if (ownerId == null || ownerId.isBlank()) {
      throw new FileValidationException("ownerId must not be blank");
    }
    if (sourceUrl == null || sourceUrl.isBlank()) {
      throw new FileValidationException("archive URL must not be blank");
    }
    URI source;
    try {
      source = URI.create(sourceUrl);
    } catch (IllegalArgumentException invalid) {
      throw new FileValidationException("archive URL is invalid", invalid);
    }
    if (!"https".equalsIgnoreCase(source.getScheme())
        || source.getHost() == null
        || source.getUserInfo() != null) {
      throw new FileValidationException("archive URL must be an HTTPS URL without user info");
    }

    if (archiveKey != null && !archiveKey.matches("[A-Za-z0-9._:-]{1,256}"))
      throw new FileValidationException("invalid archiveKey");
    // Missing keys retain the old identity; signed URLs are only transport for keyed requests.
    String identity = archiveKey == null ? ownerId.length() + ":" + ownerId + sourceUrl
        : "media-output:v1:" + ownerId.length() + ":" + ownerId + archiveKey;
    return archiveOnce.apply(identity, fileId ->
        storeFile.recoverArchive(ownerId, fileId).orElseGet(() -> {
          try (RemoteFileSource.RemoteFile remote = remoteFiles.open(source)) {
            return storeFile.handleArchive(new StoreFileCommand(
                ownerId, FilePurpose.MEDIA_RESULT, remote.mimeType(), remote.contentLength(),
                remote.body()), fileId);
          }
        }));
  }
}
