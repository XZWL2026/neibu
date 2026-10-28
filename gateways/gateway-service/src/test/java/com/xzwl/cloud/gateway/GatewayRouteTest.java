package com.xzwl.cloud.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

@SpringBootTest(properties = {
    "spring.main.web-application-type=reactive",
    "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"
})
class GatewayRouteTest {
  @Autowired RouteLocator routes;
  @Test void phoneLoginAndUserRoutesTargetTheirRealServices() {
    assertRoute("POST", "/api/v1/auth/otp/login", "lb://iam-service");
    assertRoute("PATCH", "/api/v1/admin/users/example/nickname", "lb://user-service");
    assertRoute("POST", "/api/v1/admin/users/example/nickname", null);
    assertRoute("GET", "/api/v1/users/me", "lb://user-service");
    assertRoute("PATCH", "/api/v1/users/me", "lb://user-service");
    assertRoute("GET", "/api/v1/files/avatars/file/access-url", "lb://storage-service");
    assertRoute("GET", "/api/v1/admin/operations/users", "lb://operations-service");
    assertRoute("GET", "/internal/v1/iam/session", null);
    assertRoute("PUT", "/api/v1/internal/operations/users/id", null);
    assertRoute("DELETE", "/api/v1/users/me", null);
  }
  @Test void depthProcessingUsesExplicitPublicMethodsAndKeepsInternalStoragePrivate() {
    assertRoute("GET", "/api/v1/media/depth-options", "lb://media-service");
    assertRoute("POST", "/api/v1/media/depth-tasks", "lb://media-service");
    assertRoute("POST", "/api/v1/files/processing-videos", "lb://storage-service");
    assertRoute("POST", "/api/v1/files/example/reference-clip", "lb://storage-service");
    assertRoute("GET", "/api/v1/media/depth-tasks", null);
    assertRoute("POST", "/internal/v1/files/processing-videos/access-url", null);
  }
  void assertRoute(String method, String path, String uri) {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
    var matches = routes.getRoutes().filterWhen(r -> r.getPredicate().apply(exchange))
        .collectList().block(Duration.ofSeconds(10));
    if (uri == null) assertThat(matches).isEmpty();
    else assertThat(matches).singleElement().satisfies(r -> assertThat(r.getUri().toString()).isEqualTo(uri));
  }
}
