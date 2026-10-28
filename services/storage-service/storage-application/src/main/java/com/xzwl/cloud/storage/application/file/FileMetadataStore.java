package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.util.Optional;

public interface FileMetadataStore {
  void save(StoredFile file);

  default void updateMediaMetadata(StoredFile file) {
    throw new FileStorageException("reference metadata could not be updated");
  }

  Optional<StoredFile> findById(String fileId);
}
