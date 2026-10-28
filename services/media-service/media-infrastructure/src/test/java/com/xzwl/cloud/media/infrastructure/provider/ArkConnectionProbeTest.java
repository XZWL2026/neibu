package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ArkConnectionProbeTest {
  @Test void checksSavedKeyWithoutGeneratingOrFollowingRedirectsOrReturningProviderBody() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var status=new AtomicInteger(200); var calls=new AtomicInteger();
    var received=new AtomicReference<String>();
    server.createContext("/api/v3/contents/generations/tasks", exchange->{
      calls.incrementAndGet(); received.set(exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals("GET",exchange.getRequestMethod());
      assertEquals("page_size=1",exchange.getRequestURI().getQuery());
      exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/trap");
      byte[] body="private-upstream-task-and-key".getBytes();
      exchange.sendResponseHeaders(status.get(),body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.createContext("/trap",exchange->{ calls.addAndGet(100); exchange.sendResponseHeaders(200,-1);exchange.close(); });
    server.start();
    try {
      var connection=new AtomicReference<>(new ArkConnectionStore.Connection(4,"ark","http://127.0.0.1:"+server.getAddress().getPort(),"first-secret"));
      ArkConnectionStore store=new ArkConnectionStore() {
        public Connection load(String providerId) { return connection.get(); }
        public Connection save(long v,String p,String b,String k) { throw new UnsupportedOperationException(); }
        public boolean hasConfiguredConnection() { return true; }
    public boolean editable() { return true; }
      };
      var probe=new HttpArkConnectionProbe(store,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(2));
      var ok=probe.test(connection.get().providerId()); assertTrue(ok.reachable());assertEquals(4,ok.version());assertEquals("Bearer first-secret",received.get());
      connection.set(new ArkConnectionStore.Connection(5,"ark",connection.get().baseUrl(),"rotated-secret"));
      for(int failure:new int[]{401,403,302,500}) {
        status.set(failure); var result=probe.test(connection.get().providerId());
        assertFalse(result.reachable()); assertEquals(5,result.version());
        assertFalse(result.message().contains("private-upstream"));assertFalse(result.message().contains("rotated-secret"));
      }
      assertEquals("Bearer rotated-secret",received.get());assertEquals(5,calls.get());
      connection.set(new ArkConnectionStore.Connection(6,"ark",connection.get().baseUrl(),""));
      assertFalse(probe.test(connection.get().providerId()).reachable()); assertEquals(5,calls.get());
    } finally { server.stop(0); }
  }
}
