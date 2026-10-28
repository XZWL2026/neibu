package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoutingModelMapper extends BaseMapper<RoutingModelEntity> {
  @Insert("""
      INSERT INTO routing_model_config (
        model_id, provider_id, provider_model, configuration_json, enabled
      ) VALUES (#{modelId}, #{providerId}, #{providerModel}, #{configurationJson}, #{enabled})
      ON DUPLICATE KEY UPDATE
        provider_id = VALUES(provider_id), provider_model = VALUES(provider_model),
        configuration_json = VALUES(configuration_json), enabled = VALUES(enabled), updated_at = CURRENT_TIMESTAMP(6)
      """)
  int upsert(RoutingModelEntity model);
}
