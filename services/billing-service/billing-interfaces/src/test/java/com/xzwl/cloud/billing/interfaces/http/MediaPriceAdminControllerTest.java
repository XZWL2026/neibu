package com.xzwl.cloud.billing.interfaces.http;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class MediaPriceAdminControllerTest {
  @Test void savesYuanPricesWithoutInterpretingThemAsPoints() throws Exception {
    var store = new MediaPriceStore() {
      Snapshot state = new Snapshot(1, true, List.of());
      public Snapshot load() { return state; }
      public Snapshot save(long version, List<MediaPrice> prices) { return state = new Snapshot(2, true, prices); }
    };
    var mvc = MockMvcBuilders.standaloneSetup(new MediaPriceAdminController(store)).build();
    String body = "{\"expectedVersion\":1,\"prices\":[{\"type\":\"VIDEO\",\"accountType\":\"PERSONAL\",\"model\":\"m\",\"resolution\":\"720p\",\"unit\":\"SECOND\",\"yuanPerUnit\":\"0.5000\",\"enabled\":true}]}";
    assertEquals(200, mvc.perform(put("/api/v1/admin/billing/media-prices").contentType("application/json").content(body)
        .with(r -> { r.setUserPrincipal(() -> "primary"); r.addUserRole("ADMIN"); r.addUserRole("PRIMARY_ADMIN"); return r; })).andReturn().getResponse().getStatus());
  }
  @Test void onlyPrimaryCanReadSaveAndStaleVersionReturnsConflict() throws Exception {
    var store=new MediaPriceStore() {
      Snapshot state=new Snapshot(1,false,List.of());
      public Snapshot load(){return state;}
      public Snapshot save(long version,List<MediaPrice> prices){if(version!=state.version())throw new IllegalStateException();return state=new Snapshot(version+1,true,prices);}
    };
    var mvc=MockMvcBuilders.standaloneSetup(new MediaPriceAdminController(store)).build();
    String path="/api/v1/admin/billing/media-prices";
    String body="{\"expectedVersion\":1,\"prices\":[{\"type\":\"IMAGE\",\"accountType\":\"PERSONAL\",\"model\":\"m\",\"resolution\":\"2k\",\"unit\":\"IMAGE\",\"pointsPerUnit\":\"12.3456\",\"enabled\":true}]}";
    assertEquals(401,mvc.perform(get(path).header("X-Actor-Role","ADMIN")).andReturn().getResponse().getStatus());
    for(String role:new String[]{"NORMAL_ADMIN","ADMIN"}) {
      assertEquals(403,mvc.perform(get(path).with(r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(put(path).contentType("application/json").content(body)
          .with(r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
    }
    for(int expected:new int[]{200,409}) assertEquals(expected,mvc.perform(put(path).contentType("application/json").content(body)
        .with(r->{r.setUserPrincipal(()->"primary");r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;})).andReturn().getResponse().getStatus());
    assertEquals("12.3456",store.load().prices().getFirst().pointsPerUnit());
  }
}
