package com.xzwl.cloud.promotion.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.xzwl.cloud.promotion.application.production.PromotionOperations;
import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PromotionControllerSecurityTest {
  private MockMvc mvc;
  private PromotionStore.AdminCreatePromoter createdCommand;
  private PromotionOperations.Rebind reboundCommand;

  @BeforeEach
  void setUp() {
    PromotionStore store = (PromotionStore) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {PromotionStore.class}, (proxy, method, args) -> switch (method.getName()) {
          case "listApplications", "listWithdrawals", "listCommissionEntries", "listPromoters" ->
              new PromotionStore.Page<>(List.of(), 0, 20, 0);
          case "createAdminPromoter" -> {
            createdCommand = (PromotionStore.AdminCreatePromoter) args[0];
            yield new PromotionStore.PromoterView(createdCommand.userId(), "P123456789ABCDEF",
                PromotionStore.PromoterStatus.ENABLED, createdCommand.occurredAt(), createdCommand.occurredAt());
          }
          case "submitApplication" -> {
            var command = (PromotionStore.SubmitApplication) args[0];
            yield new PromotionStore.ApplicationView(
                command.applicationId(), command.userId(), "PERSONAL", "ENABLED", "share",
                "community", 1, Instant.parse("2026-08-01T00:00:00Z"), "PENDING",
                Instant.parse("2026-09-01T00:00:00Z"), null, null, null);
          }
          case "freezeRecharge" -> new PromotionStore.CommissionView(
              "promoter-1", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
              BigDecimal.ZERO, 1, BigDecimal.ONE);
          case "projectApplicantEligibility" -> new PromotionStore.ApplicantEligibilityView(
              "user-1", "PERSONAL", "ENABLED", Instant.parse("2026-08-01T00:00:00Z"),
              1, Instant.parse("2026-09-01T00:00:00Z"));
          case "projectPayoutAccount" -> new PromotionStore.PayoutAccountView(
              "payout-1", "user-1", "BANK_CARD", "Zhang", "****5678",
              PromotionStore.PayoutAccountStatus.ACTIVE, 1,
              Instant.parse("2026-09-01T00:00:00Z"));
          case "bindReferral" -> {
            var command = (PromotionStore.BindReferral) args[0];
            yield new PromotionStore.ReferralView(command.referredUserId(),
                command.directInviterUserId(), "promoter-1", null, null,
                command.expectedVersion() + 1, command.effectiveAt());
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var controller = new PromotionController(new PromotionService(store));
    controller.setOperations((PromotionOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {PromotionOperations.class}, (proxy, method, args) -> {
          if (!method.getName().equals("rebind")) throw new UnsupportedOperationException(method.getName());
          reboundCommand = (PromotionOperations.Rebind) args[0];
          return new PromotionOperations.Attribution(new PromotionStore.ReferralView(
              reboundCommand.userId(), reboundCommand.inviterUserId(), "promoter-1", null, null,
              reboundCommand.expectedVersion() + 1, Instant.parse("2026-09-02T00:00:00Z")), List.of());
        }));
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
  }

  @Test
  void promoterListRequiresVerifiedAdministratorAndValidPagination() throws Exception {
    String endpoint = "/api/v1/admin/promotions/promoters";
    assertEquals(401, mvc.perform(get(endpoint).header("X-Actor-Role", "ADMIN")
        .header("X-User-Id", "admin-1")).andReturn().getResponse().getStatus());
    for (String role : List.of("USER", "PROMOTER", "API_USER")) {
      assertEquals(403, mvc.perform(get(endpoint).with(request -> {
        request.setUserPrincipal(() -> "user-1"); request.addUserRole(role); return request;
      })).andReturn().getResponse().getStatus());
    }
    assertEquals(200, mvc.perform(admin(get(endpoint))).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(get(endpoint).with(request -> {
      request.setUserPrincipal(() -> "normal-1"); request.addUserRole("NORMAL_ADMIN"); return request;
    })).andReturn().getResponse().getStatus());
    for (String query : List.of("?page=-1", "?size=0", "?size=101", "?userId=" + "x".repeat(129))) {
      assertEquals(400, mvc.perform(admin(get(endpoint + query))).andReturn().getResponse().getStatus());
    }
  }

  @Test
  void promoterCreationRequiresPrimaryRoleAndIgnoresForgedHeaders() throws Exception {
    String endpoint = "/api/v1/admin/promotions/promoters";
    String body = "{\"commandId\":\"create-1\",\"userId\":\"user-1\"}";
    assertEquals(401, mvc.perform(post(endpoint).header("X-Actor-Role", "ADMIN")
        .header("X-Primary-Admin", "true").contentType("application/json").content(body))
        .andReturn().getResponse().getStatus());
    for (String role : List.of("USER", "PROMOTER", "API_USER", "NORMAL_ADMIN", "ADMIN", "PRIMARY_ADMIN")) {
      assertEquals(403, mvc.perform(post(endpoint).with(request -> {
        request.setUserPrincipal(() -> "actor-1"); request.addUserRole(role); return request;
      }).header("X-Primary-Admin", "true").contentType("application/json").content(body))
          .andReturn().getResponse().getStatus());
    }
  }

  @Test
  void primaryAdministratorCreatesWithServerActorAndTimestampAndRejectsInvalidBody() throws Exception {
    String endpoint = "/api/v1/admin/promotions/promoters";
    Instant before = Instant.now();
    assertEquals(200, mvc.perform(primary(post(endpoint)).contentType("application/json")
        .content("{\"commandId\":\"create-1\",\"userId\":\"user-1\",\"adminId\":\"forged\",\"occurredAt\":\"2000-01-01T00:00:00Z\"}"))
        .andReturn().getResponse().getStatus());
    assertEquals("admin-1", createdCommand.adminId());
    assertEquals("user-1", createdCommand.userId());
    org.junit.jupiter.api.Assertions.assertFalse(createdCommand.occurredAt().isBefore(before));
    org.junit.jupiter.api.Assertions.assertFalse(createdCommand.occurredAt().isAfter(Instant.now()));
    for (String body : List.of("{}", "{\"userId\":\"user-1\"}", "{\"commandId\":\"create\"}",
        "{\"commandId\":\" \",\"userId\":\"user-1\"}", "{\"commandId\":\"create\",\"userId\":\" \"}")) {
      assertEquals(400, mvc.perform(primary(post(endpoint)).contentType("application/json").content(body))
          .andReturn().getResponse().getStatus());
    }
  }

  private static MockHttpServletRequestBuilder primary(MockHttpServletRequestBuilder request) {
    return admin(request).with(servlet -> { servlet.addUserRole("PRIMARY_ADMIN"); return servlet; });
  }

  @Test
  void forgedHeadersCannotAccessAdministratorQueries() throws Exception {
    assertEquals(401, mvc.perform(get("/api/v1/admin/promotions/applications")
        .header("X-Actor-Role", "ADMIN").header("X-User-Id", "admin-1"))
        .andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(get("/api/v1/admin/promotions/applications")
        .principal(() -> "user-1")).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(admin(get("/api/v1/admin/promotions/applications")))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void userCommandsRequireTheAuthenticatedOwner() throws Exception {
    String body = """
        {"commandId":"submit-1","applicationId":"application-1",
         "reason":"share","plannedChannel":"community","agreementsAccepted":true,
         "ignoredClientTimestamp":"2026-09-01T00:00:00Z"}
        """;
    assertEquals(401, mvc.perform(post("/api/v1/promotions/applications")
        .contentType("application/json").content(body))
        .andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(post("/api/v1/promotions/applications")
        .with(request -> { request.addUserRole("USER"); return request; }).principal(() -> "user-1").contentType("application/json").content(body))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void trustedSnapshotsRequireServiceRole() throws Exception {
    String body = """
        {"sourceEventId":"eligibility-1","accountType":"PERSONAL","accountStatus":"ENABLED",
         "registeredAt":"2026-08-01T00:00:00Z","sourceVersion":1,
         "occurredAt":"2026-09-01T00:00:00Z"}
        """;
    var endpoint = "/api/v1/internal/promotions/applicants/user-1/eligibility";
    assertEquals(403, mvc.perform(post(endpoint).principal(() -> "user-1")
        .contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(service(post(endpoint)
        .contentType("application/json").content(body))).andReturn().getResponse().getStatus());

    String payout = """
        {"sourceEventId":"payout-event-1","ownerUserId":"user-1","payoutType":"BANK_CARD",
         "payoutAccountName":"Zhang","payoutAccountReference":"provider-token-5678",
         "status":"ACTIVE","sourceVersion":1,"occurredAt":"2026-09-01T00:00:00Z"}
        """;
    var payoutEndpoint = "/api/v1/internal/promotions/payout-accounts/payout-1";
    assertEquals(403, mvc.perform(post(payoutEndpoint).principal(() -> "user-1")
        .contentType("application/json").content(payout)).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(service(post(payoutEndpoint)
        .contentType("application/json").content(payout))).andReturn().getResponse().getStatus());
  }

  @Test
  void internalSettlementRequiresServiceRole() throws Exception {
    String body = """
        {"sourceEventId":"event-1","orderId":"order-1","fundingLotId":"lot-1",
         "walletOwnerUserId":"user-1","referralBindingVersion":1,"benefitPolicyVersion":1,
         "paidAmount":100,"occurredAt":"2026-09-01T00:00:00Z"}
        """;
    var request = post("/api/v1/internal/promotions/commissions/recharges")
        .principal(() -> "service-1").contentType("application/json").content(body);
    assertEquals(403, mvc.perform(request).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(service(post("/api/v1/internal/promotions/commissions/recharges")
        .contentType("application/json").content(body))).andReturn().getResponse().getStatus());
  }

  @Test
  void usersCanOnlyCreateTheirInitialBindingAndPrimaryAdministratorsCanRebind() throws Exception {
    String initial = """
        {"commandId":"bind-1","directInviterUserId":"inviter-1","expectedVersion":0,
         "effectiveAt":"2026-09-01T00:00:00Z"}
        """;
    assertEquals(401, mvc.perform(post("/api/v1/promotions/referrals/bindings")
        .contentType("application/json").content(initial)).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(post("/api/v1/promotions/referrals/bindings")
        .principal(() -> "user-1").contentType("application/json").content(initial))
        .andReturn().getResponse().getStatus());

    String rebind = """
        {"commandId":"bind-2","directInviterUserId":"inviter-2","expectedVersion":1,
         "effectiveAt":"2026-09-02T00:00:00Z","reason":"Correct inviter","adminId":"forged"}
        """;
    assertEquals(403, mvc.perform(post("/api/v1/promotions/referrals/bindings")
        .principal(() -> "user-1").contentType("application/json").content(rebind))
        .andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(admin(post(
        "/api/v1/admin/promotions/referrals/user-1/bindings")
        .contentType("application/json").content(rebind))).andReturn().getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertNull(reboundCommand);
    assertEquals(200, mvc.perform(primary(post(
        "/api/v1/admin/promotions/referrals/user-1/bindings")
        .contentType("application/json").content(rebind))).andReturn().getResponse().getStatus());
    assertEquals(new PromotionOperations.Rebind("bind-2", "user-1", "inviter-2", 1,
        "Correct inviter", "admin-1"), reboundCommand);
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
