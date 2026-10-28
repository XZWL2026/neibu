package com.xzwl.cloud.promotion.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.promotion.application.production.*;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PortalPromotionControllerTest {
  @Test void derivesOwnerAndRejectsOtherRolesDisabledPromotersAndUntrustedInternalCallers() throws Exception {
    PromotionStore store = (PromotionStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PromotionStore.class},
        (proxy, method, args) -> switch(method.getName()) {
          case "portalEligibility" -> new PortalEligibility((String) args[0], !"disabled".equals(args[0]));
          case "portalDownlines" -> new Page<>(List.of(new PortalDownline("masked-" + args[0], null, null, null, null, null, 1, Instant.EPOCH, null, null, null)), 0, 20, 1);
          default -> throw new AssertionError(method.getName());
        });
    var service = new PromotionService(store);
    var mvc = MockMvcBuilders.standaloneSetup(new PortalPromotionController(service, "test-token"), new PromotionController(service))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    String path = "/api/v1/promotions/portal/downlines";
    assertEquals(401, mvc.perform(get(path)).andReturn().getResponse().getStatus());
    for(String role : List.of("USER", "API_USER", "ADMIN", "NORMAL_ADMIN")) {
      assertEquals(403, mvc.perform(role(get(path), "owner1", role)).andReturn().getResponse().getStatus());
    }
    for(String owner : List.of("owner1", "owner2")) {
      var result = mvc.perform(role(get(path).param("userId", "victim"), owner, "PROMOTER")).andReturn().getResponse();
      assertEquals(200, result.getStatus()); assertTrue(result.getContentAsString().contains("masked-" + owner));
      assertFalse(result.getContentAsString().contains("victim"));
    }
    assertEquals(403, mvc.perform(role(get(path), "disabled", "PROMOTER")).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(role(get("/api/v1/admin/promotions/withdrawals"), "owner1", "PROMOTER")).andReturn().getResponse().getStatus());
    String internal = "/api/v1/internal/promotions/promoters/owner1/portal-eligibility";
    assertEquals(403, mvc.perform(get(internal).header("X-Service-Token", "wrong")).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(get(internal).header("X-Service-Token", "test-token")).andReturn().getResponse().getStatus());
  }
  @Test void portalMoneyUsesDecimalStringsAcrossJsonAndKeepsExactWithdrawalInput() throws Exception {
    var amount = new java.math.BigDecimal("90071992547409931234567890123456.12");
    PromotionStore store = (PromotionStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PromotionStore.class},
        (proxy, method, args) -> switch(method.getName()) {
          case "portalEligibility" -> new PortalEligibility("owner", true);
          case "portalOverview" -> new PortalOverview(null, new CommissionView("owner", amount, amount, amount, amount, amount, 1, amount), 0, "0.0000", "0.0000", "0.0000", 0, 0, "0.0000", "0.0000");
          case "requestWithdrawal" -> {
            var command = (RequestWithdrawal)args[0]; assertEquals(amount, command.amount());
            yield new WithdrawalView("withdrawal", "owner", command.amount(), WithdrawalStatus.WITHDRAWING, "account", 1, Instant.EPOCH, "BANK_CARD", "Sensitive Name", "****1234", null, Instant.EPOCH, Instant.EPOCH);
          }
          default -> throw new AssertionError(method.getName());
        });
    var mvc = MockMvcBuilders.standaloneSetup(new PortalPromotionController(new PromotionService(store), "token"))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var overview = mapper.readTree(mvc.perform(role(get("/api/v1/promotions/portal/overview"), "owner", "PROMOTER")).andReturn().getResponse().getContentAsString());
    assertTrue(overview.path("commission").path("available").isTextual());
    assertEquals("90071992547409931234567890123456.1200", overview.path("commission").path("available").asText());
    var result = mvc.perform(role(post("/api/v1/promotions/portal/withdrawals").contentType("application/json")
        .content("{\"commandId\":\"request\",\"withdrawalId\":\"withdrawal\",\"payoutAccountId\":\"account\",\"amount\":\"90071992547409931234567890123456.12\"}"), "owner", "PROMOTER")).andReturn().getResponse();
    assertEquals(200, result.getStatus());
    assertTrue(mapper.readTree(result.getContentAsString()).path("amount").isTextual());
    assertEquals("90071992547409931234567890123456.1200", mapper.readTree(result.getContentAsString()).path("amount").asText());
    assertFalse(result.getContentAsString().contains("Sensitive Name"));
  }
  @Test void withdrawalDateParametersReachOwnerQueryAndRejectInvertedRanges() throws Exception {
    var from = Instant.parse("2026-08-01T00:00:00Z");
    var to = Instant.parse("2026-09-01T00:00:00Z");
    PromotionStore store = (PromotionStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PromotionStore.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "portalEligibility" -> new PortalEligibility((String) args[0], true);
          case "portalWithdrawals", "portalRecharges", "portalConsumptions" -> {
            assertEquals("owner", args[0]); assertEquals(from, args[1]); assertEquals(to, args[2]);
            assertEquals(1, args[3]); assertEquals(5, args[4]); yield new FinancialPage<>(List.of(), 1, 5, 7, "123.4567");
          }
          default -> throw new AssertionError(method.getName());
        });
    var mvc = MockMvcBuilders.standaloneSetup(new PortalPromotionController(new PromotionService(store), "token"))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    for (String path : List.of("/api/v1/promotions/portal/withdrawals", "/api/v1/promotions/portal/recharges", "/api/v1/promotions/portal/consumptions")) {
    var result = mvc.perform(role(get(path).param("from", from.toString()).param("to", to.toString())
        .param("page", "1").param("size", "5").param("userId", "other"), "owner", "PROMOTER")).andReturn().getResponse();
    assertEquals(200, result.getStatus()); assertTrue(result.getContentAsString().contains("\"total\":7"));
    assertTrue(result.getContentAsString().contains("\"totalAmount4\":\"123.4567\""));
    assertEquals(400, mvc.perform(role(get(path).param("from", to.toString()).param("to", from.toString()), "owner", "PROMOTER")).andReturn().getResponse().getStatus());
  }
  }
  @Test void paidMonthsAreOwnerScopedAndKeepExactMoneyWithValidatedPagination() throws Exception {
    PromotionStore store = (PromotionStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PromotionStore.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "portalEligibility" -> new PortalEligibility((String) args[0], !"disabled".equals(args[0]));
          case "portalPaidMonths" -> {
            assertEquals("owner", args[0]); assertEquals(1, args[1]); assertEquals(5, args[2]);
            yield new FinancialPage<>(List.of(new PortalPaidMonth("2026-08", "9007199254740993.1200")), 1, 5, 7, "9007199254741993.1200");
          }
          default -> throw new AssertionError(method.getName());
        });
    var mvc = MockMvcBuilders.standaloneSetup(new PortalPromotionController(new PromotionService(store), "token"))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    String path = "/api/v1/promotions/portal/paid-months";
    assertEquals(401, mvc.perform(get(path)).andReturn().getResponse().getStatus());
    for (String role : List.of("USER", "API_USER", "ADMIN", "NORMAL_ADMIN"))
      assertEquals(403, mvc.perform(role(get(path), "owner", role)).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(role(get(path), "disabled", "PROMOTER")).andReturn().getResponse().getStatus());
    var response = mvc.perform(role(get(path).param("page", "1").param("size", "5").param("userId", "victim"), "owner", "PROMOTER")).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
    assertTrue(body.path("items").get(0).path("amount4").isTextual());
    assertEquals("9007199254740993.1200", body.path("items").get(0).path("amount4").asText());
    assertEquals(400, mvc.perform(role(get(path).param("page", "-1"), "owner", "PROMOTER")).andReturn().getResponse().getStatus());
    assertEquals(400, mvc.perform(role(get(path).param("size", "0"), "owner", "PROMOTER")).andReturn().getResponse().getStatus());
  }

  private static MockHttpServletRequestBuilder role(MockHttpServletRequestBuilder request, String owner, String role) {
    return request.principal(() -> owner).with(r -> {r.addUserRole(role); return r;});
  }
}
