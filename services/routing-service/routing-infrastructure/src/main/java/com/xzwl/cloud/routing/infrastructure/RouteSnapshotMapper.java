package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RouteSnapshotMapper extends BaseMapper<RouteSnapshotEntity> {
  @Insert("""
      INSERT INTO routing_task_snapshot (
        task_id, requested_model, provider_id, provider_display_name, provider_endpoint,
        provider_model, provider_configuration_json, model_configuration_json, selected_at
      ) VALUES (#{taskId}, #{requestedModel}, #{providerId}, #{providerDisplayName},
        #{providerEndpoint}, #{providerModel}, #{providerConfigurationJson},
        #{modelConfigurationJson}, #{selectedAt})
      """)
  int insertSnapshot(RouteSnapshotEntity snapshot);
}
