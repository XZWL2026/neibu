package com.xzwl.cloud.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.WebFilter;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class PublicGatewayConfiguration {
  record PublicRoute(String service, String method, String path) {}

  @Bean RouteLocator publicRoutes(RouteLocatorBuilder builder, ObjectMapper json, Environment environment) throws IOException {
    PublicRoute[] routes;
    try (var input = new ClassPathResource("public-routes.json").getInputStream()) {
      routes = json.readValue(input, PublicRoute[].class);
    }
    var result = builder.routes();
    for (int i = 0; i < routes.length; i++) {
      var route = routes[i];
      HttpMethod method = HttpMethod.valueOf(route.method());
      // Spring MVC implicitly serves HEAD for GET mappings.
      HttpMethod[] methods = method == HttpMethod.GET ? new HttpMethod[]{method, HttpMethod.HEAD} : new HttpMethod[]{method};
      String uri = environment.getProperty("gateway.upstreams." + route.service(), "lb://" + route.service() + "-service");
      result.route(route.service() + "-" + i, r -> r.path(route.path()).and().method(methods).uri(uri));
    }
    return result.build();
  }

  @Bean @Order(-190) WebFilter browserCors(@Value("${gateway.cors.allowed-origins:}") String origins, GatewayErrors errors) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    config.setAllowCredentials(true);
    config.validateAllowCredentials();
    config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "Idempotency-Key", "X-Request-Id"));
    config.setExposedHeaders(List.of("X-Request-Id"));
    config.setMaxAge(600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", config);
    var processor = new DefaultCorsProcessor();
    return (exchange, chain) -> {
      if (!processor.process(source.getCorsConfiguration(exchange), exchange))
        return errors.write(exchange, HttpStatus.FORBIDDEN);
      if (CorsUtils.isPreFlightRequest(exchange.getRequest())) return Mono.empty();
      return chain.filter(exchange);
    };
  }
}
