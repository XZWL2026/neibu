package com.xzwl.cloud.media.infrastructure.http;

import com.fasterxml.jackson.databind.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.RouteSnapshot;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

public final class HttpRoutingPort implements RoutingPort {
  private final RestClient client;
  private final ObjectMapper json;
  public HttpRoutingPort(RestClient client,ObjectMapper json) { this.client=client; this.json=json; }
  @Override public List<Model> models() {
    try {
      ModelResponse[] response=client.get().uri("/internal/v1/models").retrieve().body(ModelResponse[].class);
      if (response==null) throw new DependencyUnavailableException("invalid model catalog");
      List<Model> result=new ArrayList<>();
      for (var model : response) {
        try {
          JsonNode configuration=json.readTree(model.configurationJson());
          validateConfiguration(configuration);
          Map<String,Object> fields=json.convertValue(configuration,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
          result.add(new Model(model.modelId(),model.providerModel(),fields,model.providerId()));
        } catch (Exception invalidConfiguration) {
          // Ignore malformed model configurations; no unsupported model is advertised.
        }
      }
      return List.copyOf(result);
    } catch (RestClientException unavailable) { throw new DependencyUnavailableException("routing unavailable"); }
  }
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
  public record ModelResponse(String modelId,String providerId,String providerModel,String configurationJson) {}
  @Override public RouteSnapshot route(String id,String model) {
    try {
      RouteSnapshot result=Objects.requireNonNull(client.post().uri("/internal/v1/tasks/{id}/route",id)
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("requestedModel",model))
          .retrieve().body(RouteSnapshot.class));
      validateConfiguration(json.readTree(result.providerConfigurationJson()));
      validateConfiguration(json.readTree(result.modelConfigurationJson()));
      return result;
    } catch(RestClientResponseException response) {
      if(Set.of(400,409,422).contains(response.getStatusCode().value())) throw new DefinitiveRejectionException("ROUTE_REJECTED");
      throw new DependencyUnavailableException("routing unavailable");
    } catch(DefinitiveRejectionException rejected) { throw rejected;
    } catch(RestClientException unavailable) { throw new DependencyUnavailableException("routing unavailable");
    } catch(Exception malformed) { throw new DefinitiveRejectionException("INVALID_ROUTE_RESPONSE"); }
  }
  private static void validateConfiguration(JsonNode node) {
    if(node==null || !node.isObject()) throw new DefinitiveRejectionException("INVALID_ROUTE_CONFIGURATION");
    scan(node);
  }
  private static void scan(JsonNode node) {
    if(node.isTextual()) {
      String value=node.textValue().toLowerCase(Locale.ROOT);
      if(value.contains("data:") || value.contains("base64")) throw new DefinitiveRejectionException("EMBEDDED_MEDIA_FORBIDDEN");
    }
    if(node.isObject()) {
      for(var field:node.properties()) {
        String key=field.getKey().replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT);
        if(key.contains("base64") || key.contains("secret") || key.contains("credential")
            || key.contains("password") || key.contains("authorization") || key.contains("privatekey")
            || key.contains("signingkey") || key.endsWith("token") || key.endsWith("apikey")
            || key.equals("accesskey") || key.equals("accesskeyid")) throw new DefinitiveRejectionException("SECRET_IN_ROUTE");
        scan(field.getValue());
      }
    } else if(node.isArray()) node.forEach(HttpRoutingPort::scan);
  }
}
