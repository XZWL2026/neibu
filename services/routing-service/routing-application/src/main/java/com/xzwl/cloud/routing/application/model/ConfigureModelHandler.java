package com.xzwl.cloud.routing.application.model;

import com.xzwl.cloud.routing.application.provider.ProviderConfigurationStore;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import java.util.Objects;

public final class ConfigureModelHandler {
  private final ProviderConfigurationStore providers;
  private final ModelConfigurationStore models;

  public ConfigureModelHandler(
      ProviderConfigurationStore providers, ModelConfigurationStore models) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.models = Objects.requireNonNull(models, "models");
  }

  public void delete(String modelId) {
    models.deleteByModelId(modelId);
  }

  public ModelConfiguration handle(ConfigureModelCommand command) {
    ConfigureModelCommand requested = Objects.requireNonNull(command, "command");
    if (providers.findById(requested.providerId()).isEmpty()) {
      throw new IllegalArgumentException("provider does not exist");
    }
    ModelConfiguration model = new ModelConfiguration(
        requested.modelId(), requested.providerId(), requested.providerModel(),
        requested.configurationJson(), requested.enabled());
    models.save(model);
    return model;
  }
}
