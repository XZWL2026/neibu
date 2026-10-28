package com.xzwl.cloud.billing.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserMediaPriceAdminControllerTest {
  private static RequestPostProcessor primary() { return r->{r.setUserPrincipal(()->"primary");r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;}; }
  @Test void onlyPrimaryCanReadWriteAuditAndTypeComesFromVerifiedOwner() throws Exception {
    var called=new AtomicReference<String>();var lookups=new AtomicInteger();
    var store=new MediaPriceStore() {
      public Snapshot load(){throw new UnsupportedOperationException();}
      public Snapshot save(long version,List<MediaPrice> prices){throw new UnsupportedOperationException();}
      public ApiSnapshot loadOwner(String owner,String type){called.set(owner+":"+type);return new ApiSnapshot(0,List.of(),List.of(),List.of());}
      public ApiSnapshot saveOwner(String owner,String type,String request,String actor,long version,List<MediaPrice> prices){assertEquals("primary",actor);assertEquals("request",request);return loadOwner(owner,type);}
      public List<ApiAudit> ownerAudit(String owner,String type){called.set(owner+":"+type);return List.of();}
    };
    var mvc=MockMvcBuilders.standaloneSetup(new UserMediaPriceAdminController(store,owner->{lookups.incrementAndGet();return new MediaBilling.Key("key",owner,owner,"API".equals(owner)?"API":"MEDIA",false);})).build();
    String path="/api/v1/admin/billing/users/PERSONAL/media-prices",body="{\"requestId\":\"request\",\"expectedVersion\":0,\"prices\":[]}";
    assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    for(String role:List.of("USER","API_USER","NORMAL_ADMIN","ADMIN")) {
      RequestPostProcessor identity=r->{r.setUserPrincipal(()->"actor");r.addUserRole(role);return r;};
      assertEquals(403,mvc.perform(get(path).with(identity).header("X-Role","PRIMARY_ADMIN")).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(get(path+"/audit").with(identity)).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(put(path).with(identity).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    }
    assertEquals(0,lookups.get());
    for(String type:List.of("PERSONAL","ENTERPRISE","API")) {
      String target=path.replace("PERSONAL",type);
      assertEquals(200,mvc.perform(get(target).with(primary())).andReturn().getResponse().getStatus());
      assertEquals(type+":"+type,called.get());
      assertEquals(200,mvc.perform(put(target).with(primary()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      assertEquals(type+":"+type,called.get());
      assertEquals(200,mvc.perform(get(target+"/audit").with(primary())).andReturn().getResponse().getStatus());
    }
    assertEquals(400,mvc.perform(get(path.replace("PERSONAL","UNKNOWN")).with(primary())).andReturn().getResponse().getStatus());
  }
  @Test void rejectsMissingMismatchedOwnersAndWrongKeyKinds() throws Exception {
    var current=new AtomicReference<MediaBilling.Key>();
    var mvc=MockMvcBuilders.standaloneSetup(new UserMediaPriceAdminController(null,owner->current.get())).build();
    String path="/api/v1/admin/billing/users/owner/media-prices";
    assertEquals(400,mvc.perform(get(path).with(primary())).andReturn().getResponse().getStatus());
    for(var key:List.of(new MediaBilling.Key("key","other","PERSONAL","MEDIA",true),new MediaBilling.Key("key","owner","PERSONAL","API",true),new MediaBilling.Key("key","owner","API","MEDIA",true))) {
      current.set(key);assertEquals(400,mvc.perform(get(path).with(primary())).andReturn().getResponse().getStatus());
    }
  }
}
