package com.xzwl.cloud.routing.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xzwl.cloud.routing.domain.provider.Provider;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigureProviderHandlerTest {

  @Test
  void savesEnabledProviderConfigurationWithoutCredentials() {
    RecordingStore store = new RecordingStore();
    Provider provider =
        new ConfigureProviderHandler(store)
            .handle(
                new ConfigureProviderCommand(
                    "volcano", "火山引擎", "https://ark.example", true,
                    "{\"timeoutSeconds\":120}"));

    assertEquals("volcano", provider.id());
    assertEquals("火山引擎", provider.displayName());
    assertEquals("https://ark.example", provider.endpoint());
    assertEquals("{\"timeoutSeconds\":120}", provider.configurationJson());
    assertTrue(provider.enabled());
    assertSame(provider, store.saved);
  }

  @Test
  void savesDisabledProviderConfiguration() {
    RecordingStore store = new RecordingStore();
    Provider provider =
        new ConfigureProviderHandler(store)
            .handle(
                new ConfigureProviderCommand(
                    "tencent-mps", "腾讯云媒体处理", "https://mps.example", false, "{}"));

    assertFalse(provider.enabled());
    assertSame(provider, store.saved);
  }

  private static final class RecordingStore implements ProviderConfigurationStore {

    private Provider saved;

    @Override
    public void save(Provider provider) {
      saved = provider;
    }

    @Override
    public Optional<Provider> findById(String providerId) {
      return Optional.ofNullable(saved).filter(provider -> provider.id().equals(providerId));
    }
  }
}
