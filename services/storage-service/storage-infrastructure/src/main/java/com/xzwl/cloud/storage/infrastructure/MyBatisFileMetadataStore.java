package com.xzwl.cloud.storage.infrastructure;

import com.xzwl.cloud.storage.application.file.FileMetadataStore;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.util.Objects;
import java.util.Optional;

public final class MyBatisFileMetadataStore implements FileMetadataStore {
  private final StoredFileMapper files;

  public MyBatisFileMetadataStore(StoredFileMapper files) {
    this.files = Objects.requireNonNull(files, "files");
  }

  @Override
  public void save(StoredFile file) {
    files.insert(StoredFileEntity.from(file));
  }

  @Override
  public void updateMediaMetadata(StoredFile file) {
    if (files.updateMediaMetadata(StoredFileEntity.from(file)) != 1)
      throw new com.xzwl.cloud.storage.application.file.FileStorageException("reference metadata update failed");
  }

  @Override
  public Optional<StoredFile> findById(String fileId) {
    return Optional.ofNullable(files.selectById(fileId)).map(StoredFileEntity::toDomain);
  }
}
