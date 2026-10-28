package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.provider.ProviderDiagnosticQuery;
import com.xzwl.cloud.media.interfaces.http.ProviderDiagnosticController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProviderDiagnosticControllerTest {
  static final String PATH="/api/v1/admin/media/provider-logs";
  static final class Query implements ProviderDiagnosticQuery {
    Filter filter;int reads;
    public Page list(Filter f) {reads++;filter=f;return new Page(List.of(),0,f.offset(),f.limit(),f.from(),f.to());}
    public TaskPage tasks(Filter f) {reads++;filter=f;return new TaskPage(List.of(),0,f.offset(),f.limit(),f.from(),f.to());}
    public Optional<Detail> find(String id) {reads++;return Optional.empty();}
  }
  MockHttpServletRequestBuilder primary(MockHttpServletRequestBuilder r) {
    return r.principal(()->"primary").with(req->{req.addUserRole("ADMIN");req.addUserRole("PRIMARY_ADMIN");return req;});
  }
  @Test void onlyVerifiedPrimaryCanReadAndResponsesAreNotCached() throws Exception {
    var query=new Query();var mvc=MockMvcBuilders.standaloneSetup(new ProviderDiagnosticController(query)).build();
    for(String path:List.of(PATH,PATH+"/tasks",PATH+"/log-id")) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      for(String role:List.of("ADMIN","NORMAL_ADMIN","PRIMARY_ADMIN","USER","PROMOTER","API_USER"))
        mvc.perform(get(path).principal(()->"other").with(req->{req.addUserRole(role);return req;})).andExpect(status().isForbidden());
    }
    assertEquals(0,query.reads);
    mvc.perform(primary(get(PATH).param("taskId","task-id"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    assertEquals("task-id",query.filter.taskId());assertEquals(0,query.filter.from());
    mvc.perform(primary(get(PATH+"/tasks").param("taskId","task-id"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    assertEquals("task-id",query.filter.taskId());assertEquals(0,query.filter.from());
    mvc.perform(primary(get(PATH+"/missing"))).andExpect(status().isNotFound());
  }
  @Test void boundsRangesAndPaginationBeforeReading() throws Exception {
    var query=new Query();var mvc=MockMvcBuilders.standaloneSetup(new ProviderDiagnosticController(query)).build();
    for(String path:List.of(PATH,PATH+"/tasks")) {
    for(var param:List.of(Map.entry("taskId","' OR 1=1"),Map.entry("phase","UNKNOWN"),Map.entry("offset","-1"),
        Map.entry("offset","10001"),Map.entry("limit","51"),Map.entry("limit","0"),Map.entry("from","-1"),Map.entry("to","0")))
      mvc.perform(primary(get(path).param(param.getKey(),param.getValue()))).andExpect(status().isBadRequest());
    mvc.perform(primary(get(path).param("from","0").param("to","9999999999999"))).andExpect(status().isBadRequest());
    }
    assertEquals(0,query.reads);
  }
}
