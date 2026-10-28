package com.xzwl.cloud.billing.infrastructure.persistence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MediaChargeMapper extends BaseMapper<MediaChargeRow> {
  @org.apache.ibatis.annotations.Insert("INSERT INTO media_charge_lock(operation_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE operation_id=operation_id")
  void lockOperation(@org.apache.ibatis.annotations.Param("id") String id);
}
