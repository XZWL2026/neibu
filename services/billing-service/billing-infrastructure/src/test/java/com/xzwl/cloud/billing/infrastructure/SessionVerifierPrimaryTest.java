package com.xzwl.cloud.billing.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.infrastructure.identity.*;
import com.xzwl.cloud.iam.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class SessionVerifierPrimaryTest {
  @Test void primaryFlagMustBeVerifiedWithAdminRole() {
    for(String role:List.of("admin","normal_admin")) for(boolean primary:List.of(false,true)) {
      var session=new SessionDTO(true,List.of(role),new UserDTO("actor","actor"),"csrf",primary);
      var verifier=new FeignSessionVerifier(new IamSessionClient() {
        public SessionDTO read(String cookie){return session;}
        public SessionDTO verify(String cookie,String csrf){return session;}
      });
      assertEquals(primary && role.equals("admin"),verifier.verify("XZWL_SESSION=fixture","csrf",true).roles().contains("PRIMARY_ADMIN"));
    }
  }
}
