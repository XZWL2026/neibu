package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.provider.ArkConnectionProbe;
import com.xzwl.cloud.media.interfaces.http.ArkConnectionProbeController;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArkConnectionProbeControllerTest {
  @Test void primaryAdminOnlyAndUpstreamFailureDoesNotLogOutAdministrator() throws Exception {
    var calls=new AtomicInteger();
    var mvc=MockMvcBuilders.standaloneSetup(new ArkConnectionProbeController(providerId->{
      calls.incrementAndGet(); return new ArkConnectionProbe.Result(7,false,"供应商拒绝鉴权或无查询权限");
    })).build();
    String path="/api/v1/admin/media/ark-connection/test";
    mvc.perform(post(path).param("providerId","ark")).andExpect(status().isUnauthorized());
    for(String role:new String[]{"NORMAL_ADMIN","ADMIN","USER"}) {
      mvc.perform(post(path).param("providerId","ark").principal(()->"admin").with(r->{r.addUserRole(role);return r;}))
          .andExpect(status().isForbidden());
    }
    assertEquals(0,calls.get());
    mvc.perform(post(path).param("providerId","ark").principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("reachable").value(false)).andExpect(jsonPath("version").value(7));
    assertEquals(1,calls.get());
  }
}
