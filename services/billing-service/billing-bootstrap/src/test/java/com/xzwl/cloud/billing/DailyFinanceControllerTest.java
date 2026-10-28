package com.xzwl.cloud.billing;

import com.xzwl.cloud.billing.application.ledger.DailyFinanceQuery;
import com.xzwl.cloud.billing.interfaces.http.AdminDailyFinanceController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DailyFinanceControllerTest {
  @Test void authorizesAndValidatesBeforeQuerying() throws Exception {
    int[] calls={0};
    var mvc=MockMvcBuilders.standaloneSetup(new AdminDailyFinanceController((from,to,offset,limit)->{
      calls[0]++;return new DailyFinanceQuery.Report(from==null?null:from.toString(),to==null?null:to.toString(),List.of(),0,offset,limit,"0.0000","0.0000");
    })).build();
    String path="/api/v1/admin/billing/daily-finance";
    assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(get(path).principal(()->"user")).andReturn().getResponse().getStatus());
    for(String[] bad:List.of(new String[]{"from","2026-09-01"},new String[]{"limit","31"},new String[]{"limit","0"},new String[]{"offset","-1"},new String[]{"offset","2147483648"})) {
      assertEquals(400,mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}).param(bad[0],bad[1])).andReturn().getResponse().getStatus());
    }
    for(String[] range:List.of(new String[]{"2026-02-30","2026-03-01"},new String[]{"2026-09-12","2026-09-11"},new String[]{"0000-01-01","9999-12-31"})) {
      assertEquals(400,mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole("ADMIN");return r;}).param("from",range[0]).param("to",range[1])).andReturn().getResponse().getStatus());
    }
    assertEquals(0,calls[0]);
    for(String role:List.of("ADMIN","NORMAL_ADMIN")) {
      var response=mvc.perform(get(path).principal(()->"admin").with(r->{r.addUserRole(role);return r;})).andReturn().getResponse();
      assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("\"limit\":30"));
    }
    assertEquals(2,calls[0]);
  }
}
