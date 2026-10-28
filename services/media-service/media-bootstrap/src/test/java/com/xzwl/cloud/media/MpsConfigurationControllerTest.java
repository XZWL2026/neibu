package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore;
import com.xzwl.cloud.media.interfaces.http.MpsConfigurationController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MpsConfigurationControllerTest {
  @Test void onlyPrimaryAdminCanRunReadOnlySavedVersionProbe() throws Exception {
    var store=mock(MpsConfigurationStore.class);
    var probe=mock(com.xzwl.cloud.media.application.provider.MpsConfigurationProbe.class);
    var mvc=MockMvcBuilders.standaloneSetup(new MpsConfigurationController(store,probe)).build();
    String body="{\"expectedVersion\":2,\"templateId\":\"fullhd\",\"aspectRatio\":\"16:9\"}";
    mvc.perform(post(PATH+"/test").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post(PATH+"/test").contentType("application/json").content(body).principal(()->"other").with(r->{r.addUserRole("NORMAL_ADMIN");return r;})).andExpect(status().isForbidden());
    verifyNoInteractions(probe,store);
    when(probe.test(2,"fullhd","16:9")).thenReturn(new com.xzwl.cloud.media.application.provider.MpsConfigurationProbe.Result(2,true,"2026-09-21T00:00:00Z",15,1920,1080,java.util.List.of(10L,20L),12000,"配置校验通过"));
    mvc.perform(post(PATH+"/test").contentType("application/json").content(body).principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;}))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("success").value(true)).andExpect(jsonPath("videoBitrateKbps").value(12000));
    when(probe.test(2,"fullhd","16:9")).thenThrow(new MpsConfigurationStore.VersionConflict());
    mvc.perform(post(PATH+"/test").contentType("application/json").content(body).principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;})).andExpect(status().isConflict());
    verifyNoInteractions(store);
  }

  static final String PATH="/api/v1/admin/media/mps-configuration";
  static final String BODY="""
      {"expectedVersion":0,"secretId":"id","secretKey":"private-key","region":"ap-beijing","bucket":"bucket-123","bucketRegion":"ap-guangzhou","outputDir":"enhance/","template720p":11,"template1080p":12,"template4k":13}
      """;
  @Test void onlyPrimaryAdminCanReadOrSaveAndSecretNeverLeavesServer() throws Exception {
    var store=mock(MpsConfigurationStore.class);
    var configuration=new MpsConfigurationStore.Configuration(1,"id","private-key","ap-beijing","bucket-123","ap-guangzhou","enhance/",11,12,13);
    when(store.load()).thenReturn(configuration);when(store.save(any())).thenReturn(configuration);
    var mvc=MockMvcBuilders.standaloneSetup(new MpsConfigurationController(store,mock(com.xzwl.cloud.media.application.provider.MpsConfigurationProbe.class))).build();
    for(boolean saving:new boolean[]{false,true}) {
      mvc.perform(saving?put(PATH).contentType("application/json").content(BODY):get(PATH)).andExpect(status().isUnauthorized());
      for(String role:new String[]{"NORMAL_ADMIN","ADMIN","PRIMARY_ADMIN","USER"}) {
        var request=saving?put(PATH).contentType("application/json").content(BODY):get(PATH);
        mvc.perform(request.principal(()->"other").with(r->{r.addUserRole(role);return r;})).andExpect(status().isForbidden());
      }
    }
    verifyNoInteractions(store);
    for(boolean saving:new boolean[]{false,true}) {
      var request=saving?put(PATH).contentType("application/json").content(BODY):get(PATH);
      var response=mvc.perform(request.principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;}))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(jsonPath("secretKeyConfigured").value(true)).andExpect(jsonPath("secretKey").doesNotExist()).andReturn().getResponse();
      assertFalse(response.getContentAsString().contains("private-key"));
    }
    when(store.save(any())).thenThrow(new MpsConfigurationStore.VersionConflict());
    mvc.perform(put(PATH).contentType("application/json").content(BODY).principal(()->"admin").with(r->{r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");return r;})).andExpect(status().isConflict());
  }
}
