package com.xzwl.cloud.operations.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OperationsRecordMapper extends BaseMapper<OperationsRecordEntity> {
  @Select("""
      SELECT record_type, record_id, user_id, business_status, amount4, occurred_at,
             summary, details_json, source_version, projected_at
        FROM operations_record WHERE record_type = #{type} AND record_id = #{recordId} FOR UPDATE
      """)
  OperationsRecordEntity lock(@Param("type") String type, @Param("recordId") String recordId);

  /** Replace the locked projection, including a nullable amount supplied by a newer source version. */
  @Update("""
      UPDATE operations_record
         SET user_id = #{userId}, business_status = #{businessStatus}, amount4 = #{amount4},
             occurred_at = #{occurredAt}, summary = #{summary}, details_json = #{detailsJson},
             source_version = #{sourceVersion}, projected_at = #{projectedAt}
       WHERE record_type = #{recordType} AND record_id = #{recordId}
      """)
  int replaceProjection(OperationsRecordEntity record);
}
