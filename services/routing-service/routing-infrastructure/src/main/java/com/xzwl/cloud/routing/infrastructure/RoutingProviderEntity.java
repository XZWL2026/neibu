package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.routing.domain.provider.Provider;

@TableName("routing_provider")
public final class RoutingProviderEntity {
  @TableId(type = IdType.INPUT)
  public String providerId;
  public String displayName;
  public String endpoint;
  public boolean enabled;
  public String configurationJson;

  static RoutingProviderEntity from(Provider provider) {
    var entity = new RoutingProviderEntity();
    entity.providerId = provider.id();
    entity.displayName = provider.displayName();
    entity.endpoint = provider.endpoint();
    entity.enabled = provider.enabled();
    entity.configurationJson = provider.configurationJson();
    return entity;
  }

  Provider toDomain() {
    return Provider.configure(providerId, displayName, endpoint, enabled, configurationJson);
  }
}
