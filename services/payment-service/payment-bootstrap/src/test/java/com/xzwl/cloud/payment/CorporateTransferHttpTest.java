package com.xzwl.cloud.payment;

import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.interfaces.http.*;
import com.xzwl.cloud.payment.application.identity.SessionVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorporateTransferHttpTest {
  static MockHttpServletRequest request(String role){var r=new MockHttpServletRequest();r.setUserPrincipal(()->"owner");r.addUserRole(role);return r;}
  @Test void rejectsInvalidProofsAndUnauthorizedRolesBeforeReadingAnyRecords()throws Exception{
    var controller=new CorporateTransferController(null,id->true);
    var file=new MockMultipartFile("file","receipt.pdf","application/pdf",CorporateTransferMysqlTest.proof().content());
    for(String role:List.of("USER","PROMOTER","ADMIN","NORMAL_ADMIN"))
      assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.submit("request","5000","",file,request(role))).getStatusCode().value());
    for(String role:List.of("USER","PROMOTER","API_USER")){
      assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.history(0,20,request(role),new MockHttpServletResponse())).getStatusCode().value());
      assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.proof("another-order",request(role),new MockHttpServletResponse())).getStatusCode().value());
    }
    assertThrows(IllegalArgumentException.class,()->controller.submit("request","5000","",new MockMultipartFile("file","a.png","image/png","<html>fake</html>".getBytes()),request("API_USER")));
    assertThrows(IllegalArgumentException.class,()->new TransferProof("a.pdf","application/pdf",new byte[TransferProof.MAX_BYTES+1]));
    assertThrows(IllegalArgumentException.class,()->new TransferProof("a.pdf","application/pdf",new byte[0]));
    assertThrows(IllegalArgumentException.class,()->new TransferProof("../a.pdf","application/pdf",file.getBytes()));
    assertThrows(IllegalArgumentException.class,()->new TransferProof("a.png","image/png",file.getBytes()));
  }
  @Test void verifiesCsrfForSubmissionAndConfirmation()throws Exception{
    SessionVerifier sessions=(cookie,csrf,write)->{if(!"session".equals(cookie)||write&&!"csrf".equals(csrf))throw new SessionVerifier.Rejected(403);return new SessionVerifier.Identity("owner",Set.of("API_USER"));};
    var filter=new PaymentSecurityFilter(sessions,"test-service-token-0000000000000000000000");
    for(String path:List.of("/api/v1/payments/corporate-transfers","/api/v1/admin/payments/recharges/id/confirm")){
      var request=new MockHttpServletRequest("POST",path);request.addHeader("Cookie","session");
      var denied=new MockHttpServletResponse();filter.doFilter(request,denied,(a,b)->fail("missing CSRF admitted"));assertEquals(403,denied.getStatus());
      request.addHeader("X-CSRF-TOKEN","csrf");var allowed=new MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
      filter.doFilter(request,allowed,(a,b)->called.set(true));assertTrue(called.get());
    }
  }
}
