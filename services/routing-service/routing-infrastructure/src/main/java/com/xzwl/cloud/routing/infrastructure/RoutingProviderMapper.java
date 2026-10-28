package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoutingProviderMapper extends BaseMapper<RoutingProviderEntity> {
  @Insert("""
      INSERT INTO routing_provider (
        provider_id, display_name, endpoint, enabled, configuration_json
      ) VALUES (#{providerId}, #{displayName}, #{endpoint}, #{enabled}, #{configurationJson})
      ON DUPLICATE KEY UPDATE
        display_name = VALUES(display_name), endpoint = VALUES(endpoint),
        enabled = VALUES(enabled), configuration_json = VALUES(configuration_json),
        updated_at = CURRENT_TIMESTAMP(6)
      """)
  int upsert(RoutingProviderEntity provider);
}
