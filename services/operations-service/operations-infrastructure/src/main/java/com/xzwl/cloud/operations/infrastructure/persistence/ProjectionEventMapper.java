package com.xzwl.cloud.operations.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface ProjectionEventMapper extends BaseMapper<ProjectionEventEntity> {
  @Insert("""
      INSERT INTO operations_projection_event
        (event_id, record_type, record_id, semantics, projected_at)
      VALUES (#{eventId}, #{recordType}, #{recordId}, #{semantics}, #{projectedAt})
      """)
  int insertReceipt(ProjectionEventEntity event);
}
