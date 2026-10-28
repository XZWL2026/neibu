package com.xzwl.cloud.routing.application.route;

import com.xzwl.cloud.routing.application.model.ModelConfigurationStore;
import com.xzwl.cloud.routing.application.provider.ProviderConfigurationStore;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class RouteTaskHandler {
  private final ProviderConfigurationStore providers;
  private final ModelConfigurationStore models;
  private final RouteSnapshotStore snapshots;
  private final Clock clock;

  public RouteTaskHandler(
      ProviderConfigurationStore providers,
      ModelConfigurationStore models,
      RouteSnapshotStore snapshots,
      Clock clock) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.models = Objects.requireNonNull(models, "models");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RouteSnapshot handle(RouteTaskCommand command) {
    RouteTaskCommand requested = Objects.requireNonNull(command, "command");
    requireNonBlank(requested.taskId(), "taskId");
    requireNonBlank(requested.requestedModel(), "requestedModel");
    RouteSnapshot existing = snapshots.findByTaskId(requested.taskId()).orElse(null);
    if (existing != null) {
      return requireSameRequestedModel(existing, requested.requestedModel());
    }
    ModelConfiguration model = models.findByModelId(requested.requestedModel()).filter(ModelConfiguration::enabled)
        .orElseThrow(() -> new RouteUnavailableException("model is not configured"));
    Provider provider = providers.findById(model.providerId())
        .filter(Provider::enabled)
        .orElseThrow(() -> new RouteUnavailableException("configured provider is unavailable"));
    RouteSnapshot snapshot = new RouteSnapshot(
        requested.taskId(), model.modelId(), provider.id(), provider.displayName(),
        provider.endpoint(), model.providerModel(), provider.configurationJson(),
        model.configurationJson(), Instant.now(clock));
    if (snapshots.saveIfAbsent(snapshot)) {
      return snapshot;
    }
    RouteSnapshot winner = snapshots.findByTaskId(requested.taskId())
        .orElseThrow(() -> new IllegalStateException(
            "route snapshot missing after concurrent insert"));
    return requireSameRequestedModel(winner, requested.requestedModel());
  }

  private static RouteSnapshot requireSameRequestedModel(
      RouteSnapshot snapshot, String requestedModel) {
    if (!snapshot.requestedModel().equals(requestedModel)) {
      throw new RouteConflictException();
    }
    return snapshot;
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
