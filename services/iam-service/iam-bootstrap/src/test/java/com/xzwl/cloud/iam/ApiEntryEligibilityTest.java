package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.iam.application.account.*;
import com.xzwl.cloud.iam.infrastructure.account.HttpPortalDirectory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ApiEntryEligibilityTest {
  @Test void selfEntryAcceptsOnlyUnboundOrApiOwnerAndFailsClosed() throws Exception {
    String token="api-entry-test-token-0000000000000000";
    var body=new AtomicReference<>("{\"ownerId\":\"owner\",\"accountType\":null}");
    var accessBody=new AtomicReference<>(body.get());
    var status=new AtomicInteger(200);
    var requests=new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("X-Service-Token"));
      byte[] bytes=(exchange.getRequestURI().getPath().endsWith("account-identity")?accessBody.get():body.get()).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.start();
    try {
      String url="http://127.0.0.1:"+server.getAddress().getPort();
      var directory=new HttpPortalDirectory(url,url,url,token);
      var identity=new UserDirectory.UserIdentity("owner","13800000000","User",true,0);
      var users=new UserDirectory(){public UserIdentity resolve(String phone){return identity;}public UserIdentity find(String id){return identity;}};
      var hasher=new PasswordHasher(){public String hash(String password){return "unused";}public boolean matches(String password,String hash){return false;}};
      var accounts=new AdminAccounts(null,hasher,users,directory);
      var principal=assertDoesNotThrow(()->accounts.portalFor(identity.principal(),"API_USER"));
      assertEquals("owner",principal.boundUserId());assertTrue(accounts.current(principal));
      assertFalse(directory.eligible("API_USER","owner"),"unbound owner cannot acquire an administrator-managed API credential");
      body.set("{\"ownerId\":\"owner\",\"accountType\":\"API\"}");
      accessBody.set(body.get());
      assertTrue(accounts.current(principal));assertTrue(directory.eligible("API_USER","owner"));
      accessBody.set("{\"ownerId\":\"owner\",\"accountType\":null}");
      body.set("{\"ownerId\":\"owner\",\"accountType\":\"PERSONAL\"}");
      assertFalse(accounts.current(principal),"Payment-bound PERSONAL cannot enter even without an Access key");
      for(String denied:new String[]{
          "{\"ownerId\":\"owner\",\"accountType\":\"PERSONAL\"}",
          "{\"ownerId\":\"owner\",\"accountType\":\"ENTERPRISE\"}",
          "{\"ownerId\":\"owner\",\"accountType\":\"UNKNOWN\"}",
          "{\"ownerId\":\"other\",\"accountType\":null}",
          "{\"ownerId\":\"other\",\"accountType\":\"API\"}","null"}){
        body.set(denied);assertFalse(accounts.current(principal));
        assertEquals(403,assertThrows(AuthFailure.class,()->accounts.portalFor(identity.principal(),"API_USER")).status());
        assertFalse(directory.eligible("API_USER","owner"));
      }
      status.set(404);assertFalse(accounts.current(principal));
      status.set(503);assertEquals(503,assertThrows(AuthFailure.class,()->accounts.current(principal)).status());
      assertEquals(503,assertThrows(AuthFailure.class,()->directory.eligible("API_USER","owner")).status());
      assertFalse(requests.isEmpty());assertEquals(java.util.Set.of("GET /internal/v1/access/owners/owner/account-identity "+token,"GET /internal/v1/payments/accounts/owner/identity "+token),new java.util.HashSet<>(requests));
    } finally {server.stop(0);}
  }
}
