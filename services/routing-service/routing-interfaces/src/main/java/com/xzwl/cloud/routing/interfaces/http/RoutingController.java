package com.xzwl.cloud.routing.interfaces.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.routing.application.model.ConfigureModelCommand;
import com.xzwl.cloud.routing.application.model.ModelConfigurationStore;
import com.xzwl.cloud.routing.application.model.ConfigureModelHandler;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderCommand;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderHandler;
import com.xzwl.cloud.routing.application.route.RouteConflictException;
import com.xzwl.cloud.routing.application.route.RouteSnapshotStore;
import com.xzwl.cloud.routing.application.route.RouteTaskCommand;
import com.xzwl.cloud.routing.application.route.RouteTaskHandler;
import com.xzwl.cloud.routing.application.route.RouteUnavailableException;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1")
public final class RoutingController {
  private static final Set<String> SECRET_KEYS = Set.of(
      "apikey", "accesskey", "accesskeyid", "secretkey", "secretaccesskey", "clientsecret",
      "password", "token", "accesstoken", "refreshtoken", "authorization", "credential",
      "credentials");

  private final ConfigureProviderHandler providers;
  private final ConfigureModelHandler models;
  private final RouteTaskHandler routes;
  private final RouteSnapshotStore snapshots;
  private final ModelConfigurationStore modelStore;

  public RoutingController(
      ConfigureProviderHandler providers,
      ConfigureModelHandler models,
      RouteTaskHandler routes,
      RouteSnapshotStore snapshots, ModelConfigurationStore modelStore) {
    this.providers = providers;
    this.models = models;
    this.routes = routes;
    this.snapshots = snapshots;
    this.modelStore = modelStore;
  }

  @GetMapping("/models")
  public java.util.List<ModelResponse> getModels(Principal principal) {
    requireAuthenticated(principal);
    return modelStore.findAll().stream().filter(ModelConfiguration::enabled).filter(model -> providers.enabled(model.providerId())).map(RoutingController::response).toList();
  }

  @PutMapping("/providers/{providerId}")
  public ProviderResponse putProvider(
      HttpServletRequest servlet,
      @PathVariable("providerId") String providerId,
      @RequestBody ProviderRequest request) {
    requireAdministrator(servlet);
    String configuration = validatedConfiguration(request.configuration());
    String endpoint = validatedEndpoint(request.endpoint());
    try {
      return response(providers.handle(new ConfigureProviderCommand(
          providerId, request.displayName(), endpoint, request.enabled(), configuration)));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
    }
  }

  @PutMapping("/models/{modelId}")
  public ModelResponse putModel(
      HttpServletRequest servlet,
      @PathVariable("modelId") String modelId,
      @RequestBody ModelRequest request) {
    requireAdministrator(servlet);
    String configuration = validatedConfiguration(request.configuration());
    try {
      return response(models.handle(new ConfigureModelCommand(
          modelId, request.providerId(), request.providerModel(), configuration, !Boolean.FALSE.equals(request.enabled()))));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
    }
  }

  @PostMapping("/tasks/{taskId}/route")
  @ResponseStatus(HttpStatus.CREATED)
  public RouteResponse route(
      Principal principal,
      @PathVariable("taskId") String taskId,
      @RequestBody RouteRequest request) {
    requireAuthenticated(principal);
    try {
      return response(routes.handle(new RouteTaskCommand(taskId, request.requestedModel())));
    } catch (RouteConflictException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
    } catch (RouteUnavailableException unavailable) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, unavailable.getMessage());
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
    }
  }

  @GetMapping("/tasks/{taskId}/route")
  public RouteResponse getRoute(Principal principal, @PathVariable("taskId") String taskId) {
    requireAuthenticated(principal);
    return snapshots.findByTaskId(taskId).map(RoutingController::response)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static String validatedConfiguration(JsonNode configuration) {
    if (configuration == null || !configuration.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configuration must be an object");
    }
    rejectSecretsAndEmbeddedData(configuration);
    return configuration.toString();
  }

  private static void requireAdministrator(HttpServletRequest request) {
    requireAuthenticated(request.getUserPrincipal());
    if (!request.isUserInRole("ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }

  private static void requireAuthenticated(Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  private static void rejectSecretsAndEmbeddedData(JsonNode node) {
    if (node.isTextual()
        && node.textValue().stripLeading().regionMatches(true, 0, "data:", 0, 5)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data URLs are not allowed");
    }
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String normalized = field.getKey().replaceAll("[^A-Za-z]", "")
            .toLowerCase(Locale.ROOT);
        if (isSensitiveKey(normalized) || normalized.contains("base64")) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "secrets and Base64 fields are not persisted");
        }
        rejectSecretsAndEmbeddedData(field.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(RoutingController::rejectSecretsAndEmbeddedData);
    }
  }

  private static boolean isSensitiveKey(String normalized) {
    return SECRET_KEYS.contains(normalized)
        || normalized.contains("secret")
        || normalized.contains("credential")
        || normalized.contains("authorization")
        || normalized.contains("password")
        || normalized.contains("privatekey")
        || normalized.contains("signingkey")
        || normalized.endsWith("token")
        || normalized.endsWith("apikey");
  }

  static String validatedEndpoint(String value) {
    try {
      URI endpoint = URI.create(value);
      if ((!"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme()))
          || endpoint.getPort() == 0 || endpoint.getPort() > 65535
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getQuery() != null
          || endpoint.getFragment() != null) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (RuntimeException invalid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "endpoint must be an HTTP or HTTPS base URL without credentials",
          invalid);
    }
  }

  private static ProviderResponse response(Provider provider) {
    return new ProviderResponse(provider.id(), provider.displayName(), provider.endpoint(),
        provider.enabled(), provider.configurationJson());
  }

  private static ModelResponse response(ModelConfiguration model) {
    return new ModelResponse(model.modelId(), model.providerId(), model.providerModel(),
        model.configurationJson());
  }

  private static RouteResponse response(RouteSnapshot snapshot) {
    return new RouteResponse(snapshot.taskId(), snapshot.requestedModel(), snapshot.providerId(),
        snapshot.providerDisplayName(), snapshot.providerEndpoint(), snapshot.providerModel(),
        snapshot.providerConfigurationJson(), snapshot.modelConfigurationJson(),
        snapshot.selectedAt());
  }

  public record ProviderRequest(
      String displayName, String endpoint, boolean enabled, JsonNode configuration) {}

  public record ProviderResponse(
      String providerId, String displayName, String endpoint, boolean enabled,
      String configurationJson) {}

  public record ModelRequest(
      String providerId, String providerModel, JsonNode configuration, Boolean enabled) {}

  public record ModelResponse(
      String modelId, String providerId, String providerModel, String configurationJson) {}

  public record RouteRequest(String requestedModel) {}

  public record RouteResponse(
      String taskId,
      String requestedModel,
      String providerId,
      String providerDisplayName,
      String providerEndpoint,
      String providerModel,
      String providerConfigurationJson,
      String modelConfigurationJson,
      Instant selectedAt) {}
}
