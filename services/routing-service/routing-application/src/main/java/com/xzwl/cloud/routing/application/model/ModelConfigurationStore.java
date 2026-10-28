package com.xzwl.cloud.routing.application.model;

import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import java.util.Optional;

public interface ModelConfigurationStore {
  void save(ModelConfiguration model);

  void deleteByModelId(String modelId);

  java.util.List<ModelConfiguration> findAll();

  Optional<ModelConfiguration> findByModelId(String modelId);
}
