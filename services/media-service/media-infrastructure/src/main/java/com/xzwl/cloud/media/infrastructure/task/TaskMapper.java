package com.xzwl.cloud.media.infrastructure.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskMapper extends BaseMapper<TaskRow> {
  @Insert("""
      INSERT INTO media_task (id,owner_id,idempotency_key,status,task_json,created_at,next_run_at,version,regeneration_source_id,operation)
      VALUES (#{id},#{ownerId},#{idempotencyKey},#{status},#{taskJson},#{createdAt},#{nextRunAt},#{version},#{regenerationSourceId},#{operation})
      ON DUPLICATE KEY UPDATE id=id
      """)
  int insertOnce(TaskRow row);

  @Select("SELECT * FROM media_task WHERE id=#{id} AND owner_id=#{owner} FOR UPDATE")
  TaskRow lockOwned(@Param("owner") String owner,@Param("id") String id);

  @Update("UPDATE media_task SET history_deleted=TRUE WHERE id=#{id} AND owner_id=#{owner}")
  int deleteHistory(@Param("owner") String owner,@Param("id") String id);

  @Update("""
      UPDATE media_task SET lease_token=#{token},lease_until=#{until}
      WHERE id=#{id} AND next_run_at<=#{now}
        AND status NOT IN ('SUCCEEDED','PARTIAL_SUCCESS','FAILED','TIMED_OUT')
        AND (lease_until IS NULL OR lease_until<=#{now})
      """)
  int claim(@Param("id") String id,@Param("token") String token,@Param("now") long now,@Param("until") long until);

  @Update("""
      UPDATE media_task SET status=#{row.status},task_json=#{row.taskJson},next_run_at=#{row.nextRunAt},
        version=#{row.version},lease_token=NULL,lease_until=NULL
      WHERE id=#{row.id} AND version=#{previousVersion} AND status=#{previousStatus}
        AND lease_token=#{token} AND lease_until>#{now}
      """)
  int advance(@Param("row") TaskRow row,@Param("previousVersion") long previousVersion,
      @Param("previousStatus") String previousStatus,@Param("token") String token,@Param("now") long now);
}
