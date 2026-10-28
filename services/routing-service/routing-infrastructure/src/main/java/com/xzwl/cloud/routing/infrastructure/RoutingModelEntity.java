package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;

@TableName("routing_model_config")
public final class RoutingModelEntity {
  @TableId(type = IdType.INPUT)
  public String modelId;
  public String providerId;
  public String providerModel;
  public String configurationJson;
  public boolean enabled;

  static RoutingModelEntity from(ModelConfiguration model) {
    var entity = new RoutingModelEntity();
    entity.modelId = model.modelId();
    entity.providerId = model.providerId();
    entity.providerModel = model.providerModel();
    entity.configurationJson = model.configurationJson();
    entity.enabled = model.enabled();
    return entity;
  }

  ModelConfiguration toDomain() {
    return new ModelConfiguration(modelId, providerId, providerModel, configurationJson, enabled);
  }
}
