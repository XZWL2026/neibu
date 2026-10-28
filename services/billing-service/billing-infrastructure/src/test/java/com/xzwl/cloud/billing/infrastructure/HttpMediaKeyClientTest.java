package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.billing.infrastructure.identity.HttpKeyOwnershipClient;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class HttpMediaKeyClientTest {
  @Test void administrationCanValidateDisabledApiOwnershipWithoutEnablingMedia() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/access/directory-keys",exchange->{
      byte[] result="[{\"ownerId\":\"owner\",\"keyId\":\"key\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
    });
    server.createContext("/internal/v1/access/keys/key",exchange->{
      byte[] result="{\"ownerId\":\"owner\",\"keyId\":\"key\",\"accountType\":\"API\",\"kind\":\"API\",\"enabled\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
    });server.start();
    try {
      var client=new HttpKeyOwnershipClient("http://127.0.0.1:"+server.getAddress().getPort(),"token");
      var key=client.forAdministrationOwner("owner");assertFalse(key.enabled());assertEquals("API",key.accountType());
      assertThrows(IllegalArgumentException.class,()->client.forAdministrationOwner("other"));
    } finally {server.stop(0);}
  }

  @Test void confirmedAccessRejectionsDoNotBecomeEndlessChargeRetries() throws Exception {
    var status=new AtomicInteger(403);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/access/owners/owner/primary-key",exchange->{
      exchange.sendResponseHeaders(status.get(),-1);exchange.close();
    });server.start();
    try {
      var client=new HttpKeyOwnershipClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-only-token");
      for(int rejection:new int[]{403,409}) {
        status.set(rejection);
        assertThrows(IllegalArgumentException.class,()->client.forOwner("owner"));
      }
      for(int unavailable:new int[]{401,500,503}) {
        status.set(unavailable);
        assertThrows(RestClientException.class,()->client.forOwner("owner"));
      }
    }finally{server.stop(0);}
  }
}
