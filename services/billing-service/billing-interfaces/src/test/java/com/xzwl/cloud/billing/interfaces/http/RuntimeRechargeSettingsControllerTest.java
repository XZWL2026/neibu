package com.xzwl.cloud.billing.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.application.recharge.SetRechargeRateHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeRechargeSettingsControllerTest {
  private static final String PATH = "/api/v1/settings/runtime";
  private RechargeRate current;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    current = new RechargeRate(Decimal4.of("10"), 1);
    RechargeRateStore store = new RechargeRateStore() {
      public RechargeRate load() { return current; }
      public void save(RechargeRate updated) {
        if (updated.version() != current.version() + 1) throw new IllegalStateException("stale");
        current = updated;
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(
        new RuntimeRechargeSettingsController(store, new SetRechargeRateHandler(store))).build();
  }

  @Test
  void anonymousOrForgedIdentityAndOrdinaryUsersCannotModifyTheRate() throws Exception {
    assertEquals(401, mvc.perform(update("\"20\"", "1")
        .header("X-Actor-Role", "ADMIN").header("X-User-Id", "admin-1"))
        .andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(update("\"20\"", "1").principal(() -> "user-1"))
        .andReturn().getResponse().getStatus());
    assertEquals(new RechargeRate(Decimal4.of("10"), 1), current);
  }

  @Test
  void administratorCanReadAndUpdateButStaleVersionReturnsConflict() throws Exception {
    var initial = mvc.perform(admin(get(PATH))).andReturn().getResponse();
    assertEquals(200, initial.getStatus());
    assertEquals("10.0000", new ObjectMapper().readTree(initial.getContentAsString())
        .path("rechargePointsPerYuan4").textValue());
    var updated = mvc.perform(admin(update("\"25.12349\"", "1"))).andReturn().getResponse();
    assertEquals(200, updated.getStatus());
    var body = new ObjectMapper().readTree(updated.getContentAsString());
    assertEquals("25.1234", body.path("rechargePointsPerYuan4").textValue());
    assertEquals(2, body.path("rechargeRateVersion").longValue());
    assertEquals(409, mvc.perform(admin(update("\"30\"", "1")))
        .andReturn().getResponse().getStatus());
    assertEquals(new RechargeRate(Decimal4.of("25.1234"), 2), current);
  }

  @Test
  void malformedTypesMissingFieldsAndInvalidRatesReturnBadRequest() throws Exception {
    for (String value : new String[] {"10", "null", "\"\"", "\"zero\"", "\"0\"", "\"-1\"", "\"0.00009\""}) {
      assertEquals(400, mvc.perform(admin(update(value, "1"))).andReturn().getResponse().getStatus());
    }
    for (String version : new String[] {"null", "\"1\"", "1.5", "0", "9223372036854775808"}) {
      assertEquals(400, mvc.perform(admin(update("\"20\"", version)))
          .andReturn().getResponse().getStatus());
    }
    assertEquals(400, mvc.perform(admin(put(PATH).contentType("application/json").content("{}")))
        .andReturn().getResponse().getStatus());
    assertEquals(new RechargeRate(Decimal4.of("10"), 1), current);
  }

  @Test
  void extremeExponentsReturnBadRequestWithoutChangingConfiguration() throws Exception {
    for (String value : new String[] {"1e2147483647", "1e-2147483647", "1e34"}) {
      assertEquals(400, mvc.perform(admin(update("\"" + value + "\"", "1")))
          .andReturn().getResponse().getStatus());
    }
    assertEquals(new RechargeRate(Decimal4.of("10"), 1), current);
  }

  private static MockHttpServletRequestBuilder update(String value, String version) {
    return put(PATH).contentType("application/json").content(
        "{\"rechargePointsPerYuan4\":" + value + ",\"rechargeRateVersion\":" + version + "}");
  }

  private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request.with(servlet -> {
      servlet.setUserPrincipal(() -> "admin-1");
      servlet.addUserRole("ADMIN");
      return servlet;
    });
  }
}
