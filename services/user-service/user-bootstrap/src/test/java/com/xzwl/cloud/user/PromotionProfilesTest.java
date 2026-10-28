package com.xzwl.cloud.user;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.user.application.identity.UserIdentityStore;
import com.xzwl.cloud.user.domain.identity.UserIdentity;
import com.xzwl.cloud.user.interfaces.http.UserIdentityController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionProfilesTest {
  @Test void batchesOnlyValidatedIdsAndKeepsRegistrationAndPhoneFacts() {
    var at=Instant.parse("2026-09-01T00:00:00Z");
    var store=(UserIdentityStore)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{UserIdentityStore.class},(proxy,method,args)->switch(method.getName()) {
      case "find" -> new UserIdentity((String)args[0],"+8613812345678","用户",true,0,null);
      case "registeredAt" -> at;
      default -> throw new UnsupportedOperationException();
    });
    var controller=new UserIdentityController(store,null);
    var profiles=controller.promotionProfiles(List.of("u1","u2"));
    assertEquals(List.of("u1","u2"),profiles.stream().map(p->p.user().id()).toList());
    assertEquals(at,profiles.getFirst().registeredAt());assertEquals("+8613812345678",profiles.getFirst().user().phone());
    assertThrows(IllegalArgumentException.class,()->controller.promotionProfiles(List.of()));
    assertThrows(IllegalArgumentException.class,()->controller.promotionProfiles(List.of("u1","u1")));
    assertThrows(IllegalArgumentException.class,()->controller.promotionProfiles(List.of("bad/id")));
    assertThrows(IllegalArgumentException.class,()->controller.promotionProfiles(java.util.stream.IntStream.range(0,101).mapToObj(i->"u"+i).toList()));
  }
}
