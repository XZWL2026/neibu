package com.xzwl.cloud.routing.application.provider;

import com.xzwl.cloud.routing.domain.provider.Provider;
import java.util.Objects;

/** Coordinates provider configuration without exposing credentials to the domain. */
public final class ConfigureProviderHandler {

  private final ProviderConfigurationStore store;

  public ConfigureProviderHandler(ProviderConfigurationStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public boolean enabled(String id) { return store.findById(id).map(Provider::enabled).orElse(false); }

  public Provider handle(ConfigureProviderCommand command) {
    ConfigureProviderCommand requested = Objects.requireNonNull(command, "command");
    Provider provider =
        Provider.configure(
            requested.providerId(),
            requested.displayName(),
            requested.endpoint(),
            requested.enabled(),
            requested.configurationJson());
    store.save(provider);
    return provider;
  }
}
