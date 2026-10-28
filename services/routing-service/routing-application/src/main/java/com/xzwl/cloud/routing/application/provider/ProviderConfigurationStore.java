package com.xzwl.cloud.routing.application.provider;

import com.xzwl.cloud.routing.domain.provider.Provider;
import java.util.Optional;

/** Persistence port for administrator-managed provider metadata. */
public interface ProviderConfigurationStore {

  default java.util.List<Provider> findProviders() { throw new UnsupportedOperationException(); }

  void save(Provider provider);

  Optional<Provider> findById(String providerId);
}
