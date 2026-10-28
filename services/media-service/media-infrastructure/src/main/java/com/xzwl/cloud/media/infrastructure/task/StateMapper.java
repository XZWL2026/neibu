package com.xzwl.cloud.media.infrastructure.task;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface StateMapper extends BaseMapper<StateRow> {
  @Insert("""
      INSERT INTO media_task_state(id,task_id,version,from_status,to_status,occurred_at)
      VALUES(#{id},#{taskId},#{version},#{fromStatus},#{toStatus},#{occurredAt})
      ON DUPLICATE KEY UPDATE id=id
      """)
  int insertOnce(StateRow row);
}
