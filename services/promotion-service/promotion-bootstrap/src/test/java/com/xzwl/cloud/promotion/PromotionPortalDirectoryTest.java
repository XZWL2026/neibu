package com.xzwl.cloud.promotion;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PromotionPortalDirectoryTest {
  @Test void administratorChecksOnlyRealUserIdentityAndEnabledStatus() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new java.util.ArrayList<String>();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath(); calls.add(path);
      assertEquals("user-token", exchange.getRequestHeaders().getFirst("X-IAM-INTERNAL-TOKEN"));
      String id = path.substring(path.lastIndexOf('/') + 1);
      if (id.equals("missing") || id.equals("failure")) {
        exchange.sendResponseHeaders(id.equals("missing") ? 404 : 503, -1); exchange.close(); return;
      }
      String body = "{\"id\":\"" + (id.equals("mismatch") ? "other" : id)
          + "\",\"enabled\":" + !id.equals("disabled") + "}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var directory = new PromotionPortalDirectory(RestClient.builder().baseUrl(base)
          .defaultHeader("X-IAM-INTERNAL-TOKEN", "user-token").build(), RestClient.builder().baseUrl(base + "/billing-must-not-be-called").build());
      assertTrue(directory.enabledUser("enabled"));
      assertFalse(directory.enabledUser("disabled"));
      assertThrows(IllegalStateException.class, () -> directory.enabledUser("mismatch"));
      assertThrows(IllegalArgumentException.class, () -> directory.enabledUser("missing"));
      assertThrows(org.springframework.web.client.RestClientException.class, () -> directory.enabledUser("failure"));
      assertEquals(java.util.List.of("/internal/v1/users/enabled", "/internal/v1/users/disabled", "/internal/v1/users/mismatch",
          "/internal/v1/users/missing", "/internal/v1/users/failure"), calls);
    } finally { server.stop(0); }
  }

  @Test void readsOwnedServiceFactsMasksPhoneAndRejectsMismatchedIdentity() throws Exception {
    var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var calls=new java.util.ArrayList<String>();
    server.createContext("/",e->{
      String body;calls.add(e.getRequestURI().getPath());
      if(e.getRequestURI().getPath().startsWith("/internal/v1/users/")) {
        assertEquals("user-token",e.getRequestHeaders().getFirst("X-IAM-INTERNAL-TOKEN"));
        body="{\"id\":\"owner\",\"nickname\":\"Real User\",\"phone\":\"13812345678\",\"enabled\":true}";
        if(e.getRequestURI().getPath().endsWith("/promotion-profile")) body="{\"user\":"+body+",\"registeredAt\":\"2026-09-17T16:00:00Z\"}";
      } else if(e.getRequestURI().getPath().contains("/allocations/")) {
        assertEquals("billing-token",e.getRequestHeaders().getFirst("X-Service-Token"));
        if(e.getRequestURI().getPath().endsWith("/unmatched")) {e.sendResponseHeaders(404,-1);e.close();return;}
        body="{\"ownerId\":\"owner\",\"allocationId\":\"allocation\",\"fundingLotId\":\"recharge:order\",\"type\":\"IMAGE\",\"points4\":\"12.3456\",\"balanceBeforePoints4\":\"100.0000\",\"balanceAfterPoints4\":\"87.6544\",\"balanceBeforeAmount4\":\"10.0000\",\"balanceAfterAmount4\":\"8.7654\"}";
      } else {
        assertEquals("billing-token",e.getRequestHeaders().getFirst("X-Service-Token"));
        body="{\"ownerId\":\"owner\",\"summary\":{\"totalCalls\":2,\"netPoints4\":\"12.3456\",\"totalRechargeAmount4\":\"100.1234\"}}";
      }
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
      e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
    });server.start();
    try {
      String base="http://127.0.0.1:"+server.getAddress().getPort();
      var directory=new PromotionPortalDirectory(RestClient.builder().baseUrl(base).defaultHeader("X-IAM-INTERNAL-TOKEN","user-token").build(),
          RestClient.builder().baseUrl(base).defaultHeader("X-Service-Token","billing-token").build());
      var facts=directory.apply("owner");
      assertEquals("/internal/v1/users/owner/promotion-profile",calls.getFirst());
      assertEquals(java.time.Instant.parse("2026-09-17T16:00:00Z"),facts.registeredAt());
      assertEquals("Real User",facts.nickname());assertEquals("138****5678",facts.maskedPhone());
      assertEquals("100.1234",facts.totalRechargeAmount4());assertEquals("12.3456",facts.consumedPoints4());
      assertThrows(IllegalStateException.class,()->directory.apply("other"));
      var allocation=directory.allocation(new com.xzwl.cloud.promotion.application.production.PromotionStore.PortalAllocationLookup("owner","allocation","recharge:order"));
      assertEquals("IMAGE",allocation.type());assertEquals("100.0000",allocation.balanceBeforePoints4());
      assertEquals("10.0000",allocation.balanceBeforeAmount4()); assertEquals("8.7654",allocation.balanceAfterAmount4());
      assertNull(directory.allocation(new com.xzwl.cloud.promotion.application.production.PromotionStore.PortalAllocationLookup("owner","unmatched","recharge:order")));
      assertThrows(IllegalStateException.class,()->directory.allocation(new com.xzwl.cloud.promotion.application.production.PromotionStore.PortalAllocationLookup("other","allocation","recharge:order")));
      assertThrows(IllegalStateException.class,()->directory.allocation(new com.xzwl.cloud.promotion.application.production.PromotionStore.PortalAllocationLookup("owner","allocation","wrong-lot")));
    } finally {server.stop(0);}
  }
}
