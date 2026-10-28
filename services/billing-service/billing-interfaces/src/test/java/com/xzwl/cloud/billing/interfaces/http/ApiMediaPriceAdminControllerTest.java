package com.xzwl.cloud.billing.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiMediaPriceAdminControllerTest {
  @Test void administratorsReadButOnlyPrimaryWritesAndTargetMustBeApi() throws Exception {
    var store=new MediaPriceStore() {
      public Snapshot load(){return new Snapshot(1,true,List.of());}
      public Snapshot save(long version,List<MediaPrice> prices){throw new UnsupportedOperationException();}
      public ApiSnapshot loadApi(String owner){return new ApiSnapshot(0,List.of(),List.of(),List.of());}
      public ApiSnapshot saveApi(String owner,String request,String actor,long version,List<MediaPrice> prices){return loadApi(owner);}
      public List<ApiAudit> apiAudit(String owner){return List.of();}
    };
    var mvc=MockMvcBuilders.standaloneSetup(new ApiMediaPriceAdminController(store,owner->new MediaBilling.Key("key",owner,owner.equals("api")?"API":"PERSONAL",owner.equals("api")?"API":"MEDIA",false))).build();
    String path="/api/v1/admin/billing/api-users/api/media-prices",body="{\"requestId\":\"save\",\"expectedVersion\":0,\"prices\":[]}";
    assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(get(path).with(r->{r.setUserPrincipal(()->"normal");r.addUserRole("NORMAL_ADMIN");return r;})).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(get(path+"/audit").with(r->{r.setUserPrincipal(()->"normal");r.addUserRole("NORMAL_ADMIN");return r;})).andReturn().getResponse().getStatus());
    for(String role:List.of("USER","NORMAL_ADMIN","ADMIN"))assertEquals(403,mvc.perform(put(path).contentType("application/json").content(body).with(r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(put(path).contentType("application/json").content(body).with(r->{r.setUserPrincipal(()->"primary");r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;})).andReturn().getResponse().getStatus());
    assertEquals(400,mvc.perform(get(path.replace("/api-users/api/", "/api-users/personal/")).with(r->{r.setUserPrincipal(()->"normal");r.addUserRole("NORMAL_ADMIN");return r;})).andReturn().getResponse().getStatus());
  }
}
