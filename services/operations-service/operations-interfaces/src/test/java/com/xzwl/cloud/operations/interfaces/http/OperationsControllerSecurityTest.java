package com.xzwl.cloud.operations.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.xzwl.cloud.operations.application.readmodel.OperationsQueryService;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperationsControllerSecurityTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    OperationsStore store = (OperationsStore) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {OperationsStore.class}, (proxy, method, args) -> switch (method.getName()) {
          case "search" -> new OperationsStore.Page(List.of(), 0, 20, 0);
          case "project" -> {
            OperationsStore.Projection p = (OperationsStore.Projection) args[0];
            yield new OperationsStore.OperationalRecord(p.type(), p.recordId(), p.userId(),
                p.status(), p.amount(), p.occurredAt(), p.summary(), p.detailsJson(),
                p.sourceVersion(), p.occurredAt());
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
    mvc = MockMvcBuilders.standaloneSetup(
        new OperationsController(new OperationsQueryService(store)))
        .setControllerAdvice(new OperationsHttpExceptionHandler()).build();
  }

  @Test
  void administratorListsRejectAnonymousForgedAndOrdinaryIdentities() throws Exception {
    assertEquals(401, mvc.perform(get("/api/v1/admin/operations/users")
        .header("X-Actor-Role", "ADMIN")).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(get("/api/v1/admin/operations/recharges")
        .principal(() -> "user-1")).andReturn().getResponse().getStatus());
    for (String resource : List.of("users", "recharges", "consumptions", "images", "videos", "promotions")) {
      assertEquals(200, mvc.perform(admin(get("/api/v1/admin/operations/" + resource)))
          .andReturn().getResponse().getStatus());
    }
  }

  @Test
  void onlyServiceIdentityCanProjectReadModels() throws Exception {
    String body = """
        {"eventId":"event-1","userId":"user-1","status":"PAID","amount":100,
         "occurredAt":"2026-09-01T00:00:00Z","summary":"recharge",
         "detailsJson":"{}","sourceVersion":1}
        """;
    var path = "/api/v1/internal/operations/recharges/recharge-1";
    assertEquals(401, mvc.perform(put(path).contentType("application/json").content(body))
        .andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(service(put(path).contentType("application/json").content(body)))
        .andReturn().getResponse().getStatus());
  }

  private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request.with(servlet -> { servlet.setUserPrincipal(() -> "admin-1");
      servlet.addUserRole("ADMIN"); return servlet; });
  }

  private static MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder request) {
    return request.with(servlet -> { servlet.setUserPrincipal(() -> "service-1");
      servlet.addUserRole("SERVICE"); return servlet; });
  }
}
