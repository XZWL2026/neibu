package com.xzwl.cloud.gateway;

import java.util.Locale;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-200)
public final class PublicBoundaryFilter implements WebFilter {
  private final GatewayErrors errors;
  public PublicBoundaryFilter(GatewayErrors errors) { this.errors = errors; }
  @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id = exchange.getRequest().getHeaders().getFirst(GatewayErrors.REQUEST_ID);
    if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) id = UUID.randomUUID().toString();
    exchange.getAttributes().put(GatewayErrors.REQUEST_ID, id);
    exchange.getResponse().getHeaders().set(GatewayErrors.REQUEST_ID, id);
    var request = exchange.getRequest().mutate().headers(headers -> headers.set(GatewayErrors.REQUEST_ID, GatewayErrors.requestId(exchange))).build();
    var normalized = exchange.mutate().request(request).build();
    String raw = request.getURI().getRawPath().toLowerCase(Locale.ROOT);
    // Do not let a proxy and a servlet disagree about path traversal, separators or matrix parameters.
    if (raw.matches(".*%(?:2e|2f|5c|25|3b).*" ) || raw.contains(";") || raw.contains("\\") || raw.contains("//")
        || raw.matches(".*(?:^|/)\\.{1,2}(?:/|$).*$")) return errors.write(normalized, HttpStatus.BAD_REQUEST);
    if (raw.equals("/internal") || raw.startsWith("/internal/")
        || raw.equals("/api/v1/internal") || raw.startsWith("/api/v1/internal/"))
      return errors.write(normalized, HttpStatus.NOT_FOUND);
    return chain.filter(normalized);
  }
}
