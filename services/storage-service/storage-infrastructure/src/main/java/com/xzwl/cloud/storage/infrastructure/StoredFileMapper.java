package com.xzwl.cloud.storage.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoredFileMapper extends BaseMapper<StoredFileEntity> {
  @org.apache.ibatis.annotations.Update("UPDATE storage_file_object SET duration_seconds=#{durationSeconds}, width=#{width}, height=#{height}, fps=#{fps} WHERE file_id=#{fileId} AND owner_id=#{ownerId} AND object_key=#{objectKey}")
  int updateMediaMetadata(StoredFileEntity file);
}
