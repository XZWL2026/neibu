package com.xzwl.cloud.routing.infrastructure;

import com.xzwl.cloud.routing.application.model.ModelConfigurationStore;
import com.xzwl.cloud.routing.application.provider.ProviderConfigurationStore;
import com.xzwl.cloud.routing.application.route.RouteSnapshotStore;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

public final class MyBatisRoutingRepository
    implements ProviderConfigurationStore, ModelConfigurationStore, RouteSnapshotStore {
  private final RoutingProviderMapper providers;
  private final RoutingModelMapper models;
  private final RouteSnapshotMapper snapshots;

  public MyBatisRoutingRepository(
      RoutingProviderMapper providers, RoutingModelMapper models, RouteSnapshotMapper snapshots) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.models = Objects.requireNonNull(models, "models");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  @Override public java.util.List<Provider> findProviders() {
    return providers.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RoutingProviderEntity>()
        .orderByAsc("provider_id")).stream().map(RoutingProviderEntity::toDomain).toList();
  }

  @Override
  public void save(Provider provider) {
    providers.upsert(RoutingProviderEntity.from(provider));
  }

  @Override
  public Optional<Provider> findById(String providerId) {
    return Optional.ofNullable(providers.selectById(providerId)).map(RoutingProviderEntity::toDomain);
  }

  @Override
  public void save(ModelConfiguration model) {
    models.upsert(RoutingModelEntity.from(model));
  }

  @Override
  public void deleteByModelId(String modelId) {
    models.deleteById(modelId);
  }

  @Override
  public Optional<ModelConfiguration> findByModelId(String modelId) {
    return Optional.ofNullable(models.selectById(modelId)).map(RoutingModelEntity::toDomain);
  }

  @Override
  public java.util.List<ModelConfiguration> findAll() {
    return models.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RoutingModelEntity>()
        .orderByAsc("model_id")).stream().map(RoutingModelEntity::toDomain).toList();
  }

  @Override
  public boolean saveIfAbsent(RouteSnapshot snapshot) {
    try {
      snapshots.insertSnapshot(RouteSnapshotEntity.from(snapshot));
      return true;
    } catch (DuplicateKeyException alreadyExists) {
      return false;
    }
  }

  @Override
  public Optional<RouteSnapshot> findByTaskId(String taskId) {
    return Optional.ofNullable(snapshots.selectById(taskId)).map(RouteSnapshotEntity::toDomain);
  }
}
