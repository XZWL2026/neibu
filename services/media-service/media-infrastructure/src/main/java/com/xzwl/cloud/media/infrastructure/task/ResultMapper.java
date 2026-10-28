package com.xzwl.cloud.media.infrastructure.task;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ResultMapper extends BaseMapper<ResultRow> {
  @Insert("""
      INSERT INTO media_task_result(id,task_id,output_index,source_url,file_id,failure_code)
      VALUES(#{id},#{taskId},#{outputIndex},#{sourceUrl},#{fileId},#{failureCode})
      ON DUPLICATE KEY UPDATE id=id
      """)
  int insertOnce(ResultRow row);
  @Update("""
      UPDATE media_task_result SET file_id=#{fileId},source_url=NULL
      WHERE id=#{id} AND file_id IS NULL AND failure_code IS NULL
      """)
  int archive(@Param("id") String id,@Param("fileId") String fileId);
  @Update("""
      UPDATE media_task_result SET failure_code=#{failureCode},source_url=NULL
      WHERE id=#{id} AND file_id IS NULL AND failure_code IS NULL
      """)
  int fail(@Param("id") String id,@Param("failureCode") String failureCode);
}
