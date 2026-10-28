package com.xzwl.cloud.routing.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.sql.Timestamp;

@TableName("routing_task_snapshot")
public final class RouteSnapshotEntity {
  @TableId(type = IdType.INPUT)
  public String taskId;
  public String requestedModel;
  public String providerId;
  public String providerDisplayName;
  public String providerEndpoint;
  public String providerModel;
  public String providerConfigurationJson;
  public String modelConfigurationJson;
  public Timestamp selectedAt;

  static RouteSnapshotEntity from(RouteSnapshot snapshot) {
    var entity = new RouteSnapshotEntity();
    entity.taskId = snapshot.taskId();
    entity.requestedModel = snapshot.requestedModel();
    entity.providerId = snapshot.providerId();
    entity.providerDisplayName = snapshot.providerDisplayName();
    entity.providerEndpoint = snapshot.providerEndpoint();
    entity.providerModel = snapshot.providerModel();
    entity.providerConfigurationJson = snapshot.providerConfigurationJson();
    entity.modelConfigurationJson = snapshot.modelConfigurationJson();
    entity.selectedAt = Timestamp.from(snapshot.selectedAt());
    return entity;
  }

  RouteSnapshot toDomain() {
    return new RouteSnapshot(taskId, requestedModel, providerId, providerDisplayName,
        providerEndpoint, providerModel, providerConfigurationJson, modelConfigurationJson,
        selectedAt.toInstant());
  }
}
