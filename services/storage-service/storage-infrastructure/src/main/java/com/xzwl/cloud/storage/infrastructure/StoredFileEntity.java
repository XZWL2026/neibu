package com.xzwl.cloud.storage.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.sql.Timestamp;

@TableName("storage_file_object")
public final class StoredFileEntity {
  @TableId(type = IdType.INPUT)
  public String fileId;
  public String ownerId;
  public String objectKey;
  public String mimeType;
  public long sizeBytes;
  public String etag;
  public String purpose;
  public Timestamp createdAt;
  public Double durationSeconds;
  public Integer width;
  public Integer height;
  public Double fps;

  static StoredFileEntity from(StoredFile file) {
    var entity = new StoredFileEntity();
    entity.fileId = file.fileId();
    entity.ownerId = file.ownerId();
    entity.objectKey = file.objectKey();
    entity.mimeType = file.mimeType();
    entity.sizeBytes = file.sizeBytes();
    entity.etag = file.etag();
    entity.purpose = file.purpose().name();
    entity.createdAt = Timestamp.from(file.createdAt());
    entity.durationSeconds = file.durationSeconds();
    entity.width = file.width();
    entity.height = file.height();
    entity.fps = file.fps();
    return entity;
  }

  StoredFile toDomain() {
    return new StoredFile(fileId, ownerId, objectKey, mimeType, sizeBytes, etag,
        FilePurpose.valueOf(purpose), createdAt.toInstant(), durationSeconds, width, height, fps);
  }
}
