package com.xzwl.cloud.routing.application.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.routing.application.model.ConfigureModelCommand;
import com.xzwl.cloud.routing.application.model.ConfigureModelHandler;
import com.xzwl.cloud.routing.application.model.ModelConfigurationStore;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderCommand;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderHandler;
import com.xzwl.cloud.routing.application.provider.ProviderConfigurationStore;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteTaskHandlerTest {
  private static final Instant SELECTED_AT = Instant.parse("2026-09-07T08:00:00Z");

  @Test
  void selectsTheModelsOnlyConfiguredProviderAndPersistsAnImmutableSnapshot() {
    MemoryRepository repository = new MemoryRepository();
    var providers = new ConfigureProviderHandler(repository);
    providers.handle(new ConfigureProviderCommand(
        "volcano", "火山引擎", "https://ark.example", true, "{\"timeoutSeconds\":120}"));
    providers.handle(new ConfigureProviderCommand(
        "other", "备用供应商", "https://other.example", true, "{}"));
    new ConfigureModelHandler(repository, repository).handle(new ConfigureModelCommand(
        "gpt-image-2", "volcano", "doubao-seedream", "{\"quality\":\"hd\"}"));

    RouteSnapshot selected = handler(repository).handle(
        new RouteTaskCommand("task-1", "gpt-image-2"));

    assertEquals("volcano", selected.providerId());
    assertEquals("doubao-seedream", selected.providerModel());
    assertEquals("https://ark.example", selected.providerEndpoint());
    assertEquals("{\"timeoutSeconds\":120}", selected.providerConfigurationJson());
    assertEquals("{\"quality\":\"hd\"}", selected.modelConfigurationJson());
    assertEquals(SELECTED_AT, selected.selectedAt());

    providers.handle(new ConfigureProviderCommand(
        "volcano", "火山引擎新版", "https://new.example", true, "{\"timeoutSeconds\":60}"));
    RouteSnapshot retried = handler(repository).handle(
        new RouteTaskCommand("task-1", "gpt-image-2"));
    assertEquals(selected, retried);
    assertEquals(selected, repository.findByTaskId("task-1").orElseThrow());

    new ConfigureModelHandler(repository, repository).handle(new ConfigureModelCommand(
        "another-model", "volcano", "another-provider-model", "{}"));
    assertThrows(RouteConflictException.class, () -> handler(repository).handle(
        new RouteTaskCommand("task-1", "another-model")));
  }

  @Test
  void disabledConfiguredProviderFailsWithoutTryingAnotherEnabledProvider() {
    MemoryRepository repository = new MemoryRepository();
    var providers = new ConfigureProviderHandler(repository);
    providers.handle(new ConfigureProviderCommand(
        "disabled", "已停用", "https://disabled.example", false, "{}"));
    providers.handle(new ConfigureProviderCommand(
        "enabled", "不应回退", "https://enabled.example", true, "{}"));
    new ConfigureModelHandler(repository, repository).handle(new ConfigureModelCommand(
        "fixed-model", "disabled", "source-model", "{}"));

    assertThrows(RouteUnavailableException.class,
        () -> handler(repository).handle(new RouteTaskCommand("task-2", "fixed-model")));
    assertEquals(Optional.empty(), repository.findByTaskId("task-2"));
  }

  @Test
  void returnsTheWinningSnapshotWhenAnotherRequestInsertsFirst() {
    MemoryRepository repository = new MemoryRepository();
    new ConfigureProviderHandler(repository).handle(new ConfigureProviderCommand(
        "volcano", "火山引擎", "https://ark.example", true, "{}"));
    new ConfigureModelHandler(repository, repository).handle(new ConfigureModelCommand(
        "gpt-image-2", "volcano", "doubao-seedream", "{}"));
    RouteSnapshot winner = new RouteSnapshot(
        "task-race", "gpt-image-2", "volcano", "火山引擎", "https://ark.example",
        "doubao-seedream", "{}", "{}", SELECTED_AT.minusSeconds(1));
    RouteSnapshotStore contestedStore = new RouteSnapshotStore() {
      private int reads;

      @Override
      public boolean saveIfAbsent(RouteSnapshot snapshot) {
        return false;
      }

      @Override
      public Optional<RouteSnapshot> findByTaskId(String taskId) {
        return reads++ == 0 ? Optional.empty() : Optional.of(winner);
      }
    };

    RouteSnapshot selected = new RouteTaskHandler(
        repository, repository, contestedStore, Clock.fixed(SELECTED_AT, ZoneOffset.UTC))
        .handle(new RouteTaskCommand("task-race", "gpt-image-2"));

    assertEquals(winner, selected);
  }

  private static RouteTaskHandler handler(MemoryRepository repository) {
    return new RouteTaskHandler(repository, repository, repository,
        Clock.fixed(SELECTED_AT, ZoneOffset.UTC));
  }

  private static final class MemoryRepository
      implements ProviderConfigurationStore, ModelConfigurationStore, RouteSnapshotStore {
    private final Map<String, Provider> providers = new HashMap<>();
    private final Map<String, ModelConfiguration> models = new HashMap<>();
    private final Map<String, RouteSnapshot> snapshots = new HashMap<>();

    @Override
    public void save(Provider provider) {
      providers.put(provider.id(), provider);
    }

    @Override
    public Optional<Provider> findById(String providerId) {
      return Optional.ofNullable(providers.get(providerId));
    }

    @Override
    public java.util.List<ModelConfiguration> findAll() {
      return models.values().stream().sorted(java.util.Comparator.comparing(ModelConfiguration::modelId)).toList();
    }
    public void save(ModelConfiguration model) {
      models.put(model.modelId(), model);
    }

    @Override
    public void deleteByModelId(String id) { models.remove(id); }
    public Optional<ModelConfiguration> findByModelId(String modelId) {
      return Optional.ofNullable(models.get(modelId));
    }

    @Override
    public boolean saveIfAbsent(RouteSnapshot snapshot) {
      return snapshots.putIfAbsent(snapshot.taskId(), snapshot) == null;
    }

    @Override
    public Optional<RouteSnapshot> findByTaskId(String taskId) {
      return Optional.ofNullable(snapshots.get(taskId));
    }
  }
}
