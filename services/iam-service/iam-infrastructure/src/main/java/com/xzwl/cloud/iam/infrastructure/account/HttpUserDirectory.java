package com.xzwl.cloud.iam.infrastructure.account;

import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.account.UserDirectory;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class HttpUserDirectory implements UserDirectory {
  private final RestClient client;
  public HttpUserDirectory(String baseUrl, String token) { this(RestClient.builder(),baseUrl,token); }
  public HttpUserDirectory(RestClient.Builder builder,String baseUrl, String token) {
    if (token == null || token.length() < 32) throw new IllegalStateException("IAM_USER_INTERNAL_TOKEN must contain at least 32 characters");
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    client = builder.clone().baseUrl(baseUrl).requestFactory(factory).defaultHeader("X-IAM-INTERNAL-TOKEN", token).build();
  }
  public UserIdentity resolve(String phone) {
    try {
      return checked(client.post().uri("/internal/v1/users/resolve").body(Map.of("phone", phone)).retrieve().body(UserIdentity.class));
    } catch (RestClientException e) { throw new AuthFailure(503, "USER_DIRECTORY_UNAVAILABLE"); }
  }
  public UserIdentity find(String id) {
    try {
      return checked(client.get().uri("/internal/v1/users/{id}", id).retrieve().body(UserIdentity.class));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) return null;
      throw new AuthFailure(503, "USER_DIRECTORY_UNAVAILABLE");
    } catch (RestClientException e) { throw new AuthFailure(503, "USER_DIRECTORY_UNAVAILABLE"); }
  }
  private UserIdentity checked(UserIdentity user) {
    if (user == null || user.id() == null || user.phone() == null) throw new AuthFailure(503, "USER_DIRECTORY_UNAVAILABLE");
    return user;
  }
}
