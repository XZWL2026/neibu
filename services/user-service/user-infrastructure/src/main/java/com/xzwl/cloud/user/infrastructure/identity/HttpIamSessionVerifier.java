package com.xzwl.cloud.user.infrastructure.identity;

import com.xzwl.cloud.user.application.identity.SessionVerifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

public final class HttpIamSessionVerifier implements SessionVerifier {
  private final RestClient client;
  public HttpIamSessionVerifier(String url) { this(RestClient.builder(),url); }
  public HttpIamSessionVerifier(RestClient.Builder builder,String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
    client = builder.clone().baseUrl(url).requestFactory(factory).build();
  }
  public Identity verify(String cookie, String csrf, boolean mutation) {
    if (cookie == null || cookie.isBlank()) throw new Rejected(401);
    if (mutation && (csrf == null || csrf.isBlank())) throw new Rejected(403);
    try {
      RestClient.RequestHeadersSpec<?> request = mutation
          ? client.post().uri("/internal/v1/iam/session/verify").body(Map.of())
          : client.get().uri("/internal/v1/iam/session");
      request.header("Cookie", cookie);
      if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
      Verification result = request.retrieve().body(Verification.class);
      if (result == null || !result.authenticated() || result.user() == null || result.roles() == null) throw new Rejected(401);
      if (result.roles().stream().anyMatch(role -> !List.of("admin", "normal_admin", "user", "api_user", "promoter").contains(role))) throw new Rejected(403);
      return new Identity(result.user().id(), result.roles());
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      throw new Rejected(code == 401 || code == 403 ? code : 503);
    } catch (RestClientException e) { throw new Rejected(503); }
  }
  public record Verification(boolean authenticated, List<String> roles, User user) {}
  public record User(String id, String username) {}
}
