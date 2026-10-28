package com.xzwl.cloud.user.infrastructure.identity;

import com.xzwl.cloud.user.application.identity.AvatarVerifier;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

/** Storage independently verifies the same session owner and AVATAR purpose. */
public final class HttpAvatarVerifier implements AvatarVerifier {
  private final RestClient client;
  public HttpAvatarVerifier(String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }
  public void verify(String fileId, String cookie) {
    if (cookie == null || cookie.isBlank()) throw new Rejected(401);
    if (!fileId.matches("[A-Za-z0-9_-]{1,128}")) throw new Rejected(400);
    try {
      client.get().uri("/api/v1/files/avatars/{id}/access-url", fileId).header("Cookie", cookie).retrieve().toBodilessEntity();
    } catch (RestClientResponseException failure) {
      int code = failure.getStatusCode().value();
      throw new Rejected(code == 400 || code == 401 || code == 403 || code == 404 ? code : 503);
    } catch (RestClientException failure) { throw new Rejected(503); }
  }
}
