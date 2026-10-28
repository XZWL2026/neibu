package com.xzwl.cloud.billing.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MediaPriceCatalogControllerTest {
  @Test void authenticatedUserReadsCurrentPricesWithoutApiOrDisabledRows() throws Exception {
    var prices = new MediaPriceStore() {
      Snapshot state = new Snapshot(1, true, List.of(
          new MediaPrice("VIDEO", "PERSONAL", "model-video", "720p", "SECOND", null, "0.1234", true),
          new MediaPrice("VIDEO", "ENTERPRISE", "model-video", "720p", "SECOND", "1.0000", true),
          new MediaPrice("IMAGE", "PERSONAL", "model-image", "2k", "IMAGE", null, "2.3456", true),
          new MediaPrice("VIDEO", "API", "api-only", "720p", "SECOND", "9", true),
          new MediaPrice("VIDEO", "PERSONAL", "disabled", "720p", "SECOND", "9", false)));
      public Snapshot load() { return state; }
      public Snapshot save(long version, List<MediaPrice> next) { return state = new Snapshot(version + 1, true, next); }
    };
    var rates = new RechargeRateStore() {
      RechargeRate rate = new RechargeRate(Decimal4.of("3.0000"), 1);
      public RechargeRate load() { return rate; }
      public void save(RechargeRate next) { rate = next; }
    };
    var mvc = MockMvcBuilders.standaloneSetup(new MediaPriceCatalogController(prices, rates)).build();
    String path = "/api/v1/billing/model-prices";
    assertEquals(401, mvc.perform(get(path).header("X-Actor-Role", "USER")).andReturn().getResponse().getStatus());
    for (String role : List.of("API_USER", "ADMIN", "NORMAL_ADMIN")) {
      assertEquals(403, mvc.perform(get(path).with(r -> {
        r.setUserPrincipal(() -> "actor"); r.addUserRole(role); return r;
      })).andReturn().getResponse().getStatus());
    }
    var request = get(path).with(r -> { r.setUserPrincipal(() -> "user"); r.addUserRole("USER"); return r; });
    var response = mvc.perform(request).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    var body = new ObjectMapper().readTree(response.getContentAsString());
    assertEquals("3.0000", body.path("pointsPerYuan4").asText());
    assertEquals(3, body.path("prices").size());
    assertEquals("0.1234", body.path("prices").get(0).path("yuanPerUnit").asText());
    assertEquals("0.3333", body.path("prices").get(1).path("yuanPerUnit").asText());
    assertEquals("ENTERPRISE", body.path("prices").get(1).path("accountType").asText());
    assertEquals("2.3456", body.path("prices").get(2).path("yuanPerUnit").asText());
    assertEquals("IMAGE", body.path("prices").get(2).path("unit").asText());
    assertFalse(response.getContentAsString().contains("api-only"));
    assertFalse(response.getContentAsString().contains("disabled"));
    rates.save(new RechargeRate(Decimal4.of("10"), 2));
    body = new ObjectMapper().readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    assertEquals("0.1234", body.path("prices").get(0).path("yuanPerUnit").asText());
    assertEquals("0.1000", body.path("prices").get(1).path("yuanPerUnit").asText());
    prices.save(1, List.of());
    body = new ObjectMapper().readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    assertTrue(body.path("prices").isEmpty());
  }
}
