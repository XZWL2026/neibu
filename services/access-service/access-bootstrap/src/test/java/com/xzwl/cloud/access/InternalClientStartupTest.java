package com.xzwl.cloud.access;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClient;

class InternalClientStartupTest {
  @Test void realApplicationProvidesInternalClientsWithoutReplacingItsErrorAdvice() throws Exception {
    for (boolean discovery : new boolean[]{false, true}) {
      try (var app = assertDoesNotThrow(() -> (ServletWebServerApplicationContext)
          new SpringApplicationBuilder(TemplateApplication.class).web(WebApplicationType.SERVLET)
              .initializers(context -> context.getBeanFactory().registerSingleton("internalClientErrorProbe", new ErrorProbe()))
              .run("--spring.profiles.active=internal-client-test", "--spring.main.lazy-initialization=true",
                  "--spring.main.banner-mode=off", "--logging.level.root=ERROR", "--server.port=0", "--server.address=127.0.0.1",
                  "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/unused", "--spring.datasource.username=root",
                  "--spring.datasource.password=", "--spring.flyway.enabled=false",
                  "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false",
                  "--spring.cloud.nacos.config.import-check.enabled=false", "--xzwl.discovery.enabled=" + discovery,
                  "--abp.internal-token=" + "t".repeat(32), "--access.user-internal-token=" + "t".repeat(32),
                  "--access.key-encryption-key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                  "--payment.worker-enabled=false", "--payment.provider=disabled"))) {
        assertNotNull(app.getBean("internalRestClientBuilder", RestClient.Builder.class));
        assertNotNull(app.getBean(com.xzwl.cloud.access.infrastructure.identity.HttpIdentityClient.class));
        var advice = app.getBeansWithAnnotation(RestControllerAdvice.class).values();
        assertEquals(1, advice.size());
        assertInstanceOf(com.xzwl.cloud.access.interfaces.http.AccessErrors.class, advice.iterator().next());
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + "/fixture/error")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("{\"code\":\"KEY_STATE_CONFLICT\"}", response.body());
      }
    }
  }

  // Non-static: not a component-scan candidate in sibling application tests.
  @RestController
  class ErrorProbe {
    @GetMapping("/fixture/error") public String fail() { throw new IllegalStateException("fixture conflict"); }
  }
}
