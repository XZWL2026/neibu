package com.xzwl.cloud.billing;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.application.identity.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.interfaces.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class BillingHttpMysqlTest {
  @Test void controllersRequireVerifiedOwnerAndInternalCommandsRequireServiceToken()throws Exception{
    var ownership=(KeyOwnershipPort)(id->new KeyOwnershipPort.Key(id,"owner",true));
    var ledger=new BillingLedgerStore(){
      public com.xzwl.cloud.billing.domain.wallet.WalletBalance balance(String id){return com.xzwl.cloud.billing.domain.wallet.WalletBalance.zero(id);}
      public com.xzwl.cloud.billing.domain.wallet.WalletBalance debit(Debit command){throw new AssertionError();}
      public List<Adjustment> adjustments(String key){return List.of();}
      public com.xzwl.cloud.billing.domain.wallet.WalletBalance consume(String w,String o,Decimal4 p){throw new AssertionError();}
      public com.xzwl.cloud.billing.domain.wallet.WalletBalance refund(String w,String r,String o,Decimal4 p){throw new AssertionError();}
      public com.xzwl.cloud.billing.domain.wallet.WalletBalance reserveRechargeRefund(String o){throw new AssertionError();}
      public List<Entry> entries(String w,boolean admin,int limit){return List.of();}
    };
    var controller=new BillingWalletController(ledger,ownership);
    var mvc=MockMvcBuilders.standaloneSetup(controller).build();
    mvc.perform(get("/api/v1/billing/wallets/key")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/billing/wallets/key").principal(()->"other")).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/billing/wallets/key").principal(()->"owner")).andExpect(status().isOk());
    var filter=new BillingInternalFilter("test-only-service-token-0000000000000000");
    var request=new MockHttpServletRequest("POST","/internal/v1/billing/recharges");
    var response=new MockHttpServletResponse();
    filter.doFilter(request,response,(req,res)->{throw new AssertionError("untrusted internal request reached controller");});
    assertEquals(401,response.getStatus());
  }
}
