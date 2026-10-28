package com.xzwl.cloud.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {"spring.profiles.active=local", "spring.main.web-application-type=reactive"})
class GatewayLocalRouteTest {
  @Autowired RouteLocator routes;
  @Autowired Environment environment;
  @Test void localProfileRetainsAllReleaseRoutesWithLoopbackDestinations() {
    var all = routes.getRoutes().collectList().block(Duration.ofSeconds(10));
    assertThat(all).hasSizeGreaterThan(50);
    assertThat(all).allSatisfy(r -> assertThat(r.getUri().getHost()).isEqualTo("127.0.0.1"));
    assertThat(all.stream().map(r -> r.getUri().getPort()).distinct().toList())
        .containsExactlyInAnyOrder(8210, 8211, 8212, 8213, 8214, 8215, 8216, 8217, 8218, 8219, 8220);
    assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isFalse();
  }
}
