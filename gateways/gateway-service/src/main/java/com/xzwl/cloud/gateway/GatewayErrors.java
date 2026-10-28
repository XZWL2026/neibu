package com.xzwl.cloud.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Only locally generated failures are encoded here; upstream responses remain byte-for-byte intact. */
@Component
@Order(-2)
public final class GatewayErrors implements WebExceptionHandler {
  static final String REQUEST_ID = "X-Request-Id";
  private final ObjectMapper json;
  public GatewayErrors(ObjectMapper json) { this.json = json; }

  static String requestId(ServerWebExchange exchange) {
    return exchange.getAttributeOrDefault(REQUEST_ID, UUID.randomUUID().toString());
  }
  Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status) {
    var response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().setCacheControl("no-store");
    String id = requestId(exchange);
    response.getHeaders().set(REQUEST_ID, id);
    HttpStatus known = HttpStatus.resolve(status.value());
    try {
      byte[] body = json.writeValueAsBytes(Map.of("code", known == null ? "HTTP_" + status.value() : known.name(),
          "message", known == null ? "HTTP error" : known.getReasonPhrase(), "requestId", id, "details", Map.of()));
      return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    } catch (JsonProcessingException impossible) { return Mono.error(impossible); }
  }
  @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
    if (exchange.getResponse().isCommitted()) return Mono.error(failure);
    HttpStatusCode status = failure instanceof ResponseStatusException e ? e.getStatusCode()
        : failure instanceof java.io.IOException ? HttpStatus.BAD_GATEWAY : HttpStatus.INTERNAL_SERVER_ERROR;
    return write(exchange, status);
  }
}
