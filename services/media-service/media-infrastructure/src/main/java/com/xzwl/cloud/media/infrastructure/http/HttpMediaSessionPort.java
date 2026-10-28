package com.xzwl.cloud.media.infrastructure.http;
import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.MediaSessionPort;
import com.xzwl.cloud.media.domain.task.MediaReference;
import org.springframework.web.client.*;

public final class HttpMediaSessionPort implements MediaSessionPort {
  private final RestClient client;
  public HttpMediaSessionPort(RestClient client) { this.client=client; }
  @Override public String verify(String cookie,String csrf,boolean write) {
    if(write && (csrf==null || csrf.isBlank())) throw new Denied(403);
    try {
      JsonNode session=write
          ? client.post().uri("/internal/v1/iam/session/verify").header("Cookie",cookie).header("X-CSRF-TOKEN",csrf).retrieve().body(JsonNode.class)
          : client.get().uri("/api/v1/auth/session").header("Cookie",cookie).retrieve().body(JsonNode.class);
      if(session==null || !session.path("authenticated").asBoolean(false) || !session.path("user").path("id").isTextual())
        throw new Denied(401);
      return MediaReference.identifier(session.path("user").path("id").textValue(),"userId");
    } catch(RestClientResponseException response) {
      int status=response.getStatusCode().value(); throw new Denied(status==401 || status==403 ? status:503);
    } catch(RestClientException unavailable) { throw new Denied(503);
    } catch(IllegalArgumentException invalid) { throw new Denied(401); }
  }
}
