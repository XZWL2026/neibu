package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.provider.ArkModelCatalog;
import com.xzwl.cloud.media.interfaces.http.ArkModelCatalogController;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArkModelCatalogControllerTest {
  @Test void primaryAdminOnlyAndUpstreamFailureDoesNotLogOutAdministrator() throws Exception {
    var calls=new AtomicInteger();
    var mvc=MockMvcBuilders.standaloneSetup(new ArkModelCatalogController(providerId->{
      calls.incrementAndGet(); return new ArkModelCatalog.Result(7,"ark",false,java.util.List.of(),"供应商拒绝鉴权或无查询权限");
    })).build();
    String path="/api/v1/admin/media/ark-connection/models";
    mvc.perform(get(path).param("providerId","ark")).andExpect(status().isUnauthorized());
    for(String role:new String[]{"NORMAL_ADMIN","ADMIN","USER"}) {
      mvc.perform(get(path).param("providerId","ark").principal(()->"admin").with(r->{r.addUserRole(role);return r;}))
          .andExpect(status().isForbidden());
    }
    assertEquals(0,calls.get());
    mvc.perform(get(path).param("providerId","ark").principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("success").value(false)).andExpect(jsonPath("version").value(7));
    assertEquals(1,calls.get());
  }
}
