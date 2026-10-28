package com.xzwl.cloud.billing.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.identity.SessionVerifier;
import com.xzwl.cloud.billing.application.identity.SessionVerifier.Identity;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.application.recharge.SetRechargeRateHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import com.xzwl.cloud.web.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrowserSessionFilterTest {
  private static final String PATH = "/api/v1/settings/runtime";
  private static final Cookie COOKIE = new Cookie("XZWL_SESSION", "test-session");

  @Test
  void verifiedAdministratorCanReadAndWriteAndUnsafeRequestsVerifyCsrf() throws Exception {
    AtomicInteger verifiedWrites = new AtomicInteger();
    MockMvc mvc = mvc((cookie, csrf, unsafe) -> {
      assertEquals("XZWL_SESSION=test-session", cookie);
      if (unsafe) {
        assertEquals("csrf-from-iam", csrf);
        verifiedWrites.incrementAndGet();
      }
      return new Identity("administrator", Set.of("ADMIN"));
    });
    assertEquals(200, mvc.perform(get(PATH).cookie(COOKIE)).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(put(PATH).cookie(COOKIE).header("X-CSRF-TOKEN", "csrf-from-iam")
        .contentType("application/json").content("{\"rechargePointsPerYuan4\":\"20\",\"rechargeRateVersion\":1}"))
        .andReturn().getResponse().getStatus());
    assertEquals(1, verifiedWrites.get());
  }

  @Test
  void forgedHeadersCannotReplaceAuthenticationAndOrdinaryUsersAreForbidden() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    MockMvc mvc = mvc((cookie, csrf, unsafe) -> {
      calls.incrementAndGet();
      return new Identity("user-1", Set.of("USER"));
    });
    assertEquals(401, mvc.perform(get(PATH).header("X-User-Id", "administrator").header("X-Actor-Role", "ADMIN"))
        .andReturn().getResponse().getStatus());
    assertEquals(0, calls.get());
    assertEquals(403, mvc.perform(get(PATH).cookie(COOKIE)).andReturn().getResponse().getStatus());
  }

  @Test
  void failedCsrfAndUnavailableIamFailClosed() throws Exception {
    MockMvc csrfDenied = mvc((cookie, csrf, unsafe) -> { throw new SessionVerifier.CsrfDenied(); });
    assertEquals(403, csrfDenied.perform(put(PATH).cookie(COOKIE).contentType("application/json").content("{}"))
        .andReturn().getResponse().getStatus());
    MockMvc unavailable = mvc((cookie, csrf, unsafe) -> { throw new SessionVerifier.Unavailable(); });
    var response = unavailable.perform(get(PATH).cookie(COOKIE)).andReturn().getResponse();
    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString().contains("SERVICE_UNAVAILABLE"));
  }

  @Test void apiRoleCannotReachBillingMutationsOrAdministratorReads() throws Exception {
    var filter = new BrowserSessionFilter((cookie,csrf,unsafe)->new Identity("owner",Set.of("API_USER")),new ObjectMapper());
    for(String path:List.of("/api/v1/admin/billing/consumptions", "/api/v1/settings/runtime", "/api/v1/admin/billing/credits")) {
      var request=new org.springframework.mock.web.MockHttpServletRequest("GET",path);request.setCookies(COOKIE);request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE,"portal-test");
      var response=new org.springframework.mock.web.MockHttpServletResponse();
      filter.doFilter(request,response,(req,res)->{throw new AssertionError("Unexpected authorization");});
      assertEquals(403,response.getStatus());
    }
    var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/billing/wallets/own-key");request.setCookies(COOKIE);request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE,"portal-test");
    var called=new java.util.concurrent.atomic.AtomicBoolean();
    filter.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)->called.set(true));
    assertTrue(called.get());
  }
  private static MockMvc mvc(SessionVerifier verifier) {
    RechargeRateStore rates = new RechargeRateStore() {
      RechargeRate current = new RechargeRate(Decimal4.of("10"), 1);
      public RechargeRate load() { return current; }
      public void save(RechargeRate updated) { current = updated; }
    };
    return MockMvcBuilders.standaloneSetup(new RuntimeRechargeSettingsController(rates, new SetRechargeRateHandler(rates)))
        .addFilters(new RequestIdFilter(), new BrowserSessionFilter(verifier, new ObjectMapper())).build();
  }
}
