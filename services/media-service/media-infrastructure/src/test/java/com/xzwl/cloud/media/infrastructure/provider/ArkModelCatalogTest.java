package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ArkModelCatalogTest {
  @Test void usesExactDocumentedVersionsAndSystemSupportedCapabilities() {
    var video=ArkModelCapabilities.forModel("doubao-seedance-2-5-260628","VIDEO");
    assertEquals(java.util.List.of("480p","720p","1080p"),video.resolutions());
    assertEquals(4,video.minDurationSeconds());assertEquals(30,video.maxDurationSeconds());assertEquals(1,video.maxCount());
    assertEquals(java.util.List.of("480p","720p"),ArkModelCapabilities.forModel("doubao-seedance-2-0-fast-260128","VIDEO").resolutions());
    var image=ArkModelCapabilities.forModel("doubao-seedream-4-5-251128","IMAGE");
    assertEquals(java.util.List.of("2K","4K"),image.resolutions());assertEquals(4,image.maxCount());
    assertEquals("2848x1600",image.sizes().get("2K").get("16:9"));
    var pro=ArkModelCapabilities.forModel("doubao-seedream-5-0-pro-260628","IMAGE");
    assertEquals(java.util.List.of("1K","1.5K","2K"),pro.resolutions());assertEquals(1,pro.maxCount());
    assertEquals("2048x1152",pro.sizes().get("1.5K").get("16:9"));
    assertTrue(video.aspectRatios().contains("21:9"));
    assertEquals("3136x1344",image.sizes().get("2K").get("21:9"));
    assertEquals("2352x1008",pro.sizes().get("1.5K").get("21:9"));
    assertTrue(ZexMediaAdapter.capability("gpt-image-2.5").aspectRatios().contains("21:9"));
    assertFalse(ZexMediaAdapter.capability("omni_flash-10s").aspectRatios().contains("21:9"));
    assertNull(ArkModelCapabilities.forModel("doubao-seedance-2-5-future","VIDEO"));
    assertNull(ArkModelCapabilities.forModel("doubao-seedance-2-5-260628","IMAGE"));
  }
  @Test void discoversActualOutputTypesWithSavedConnectionAndHandlesFailureWithoutLeakingUpstreamData() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var status=new AtomicInteger(200); var calls=new AtomicInteger(); var auth=new AtomicReference<String>();
    var body=new AtomicReference<>("""
        {"data":[
          {"id":"real-image-id","name":"图片模型","modalities":{"output_modalities":["image"]}},
          {"id":"real-video-id","name":"视频模型","status":"Shutdown","modalities":{"output_modalities":["video"]}},
          {"id":"seedream-named-chat","modalities":{"output_modalities":["text"]}},
          {"id":"unknown-model"}]}
        """);
    server.createContext("/api/v3/models", exchange->{
      calls.incrementAndGet();auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals("GET",exchange.getRequestMethod());
      exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/trap");
      byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.createContext("/trap",exchange->{calls.addAndGet(100);exchange.sendResponseHeaders(200,-1);exchange.close();});
    server.start();
    try {
      var connection=new AtomicReference<>(new ArkConnectionStore.Connection(4,"ark","http://127.0.0.1:"+server.getAddress().getPort(),"first-secret"));
      ArkConnectionStore store=new ArkConnectionStore() {
        public Connection load(String providerId){return connection.get();}
        public Connection save(long v,String p,String b,String k){throw new UnsupportedOperationException();}
        public boolean hasConfiguredConnection() { return true; }
    public boolean editable(){return true;}
      };
      var catalog=new HttpArkModelCatalog(store,new ProviderHttp(HttpClient.newHttpClient(),new ObjectMapper(),Duration.ofSeconds(2)));
      var result=catalog.list(connection.get().providerId());assertTrue(result.success());assertEquals(4,result.version());assertEquals("ark",result.providerId());
      assertEquals(2,result.models().size());assertEquals("real-image-id",result.models().get(0).id());
      assertEquals("IMAGE",result.models().get(0).type());assertEquals("VIDEO",result.models().get(1).type());
      assertEquals("Shutdown",result.models().get(1).status());assertEquals("Bearer first-secret",auth.get());
      connection.set(new ArkConnectionStore.Connection(5,"new-ark",connection.get().baseUrl(),"rotated-secret"));
      for(int code:new int[]{401,403,302,500}) {
        status.set(code);body.set("upstream-private-key");result=catalog.list(connection.get().providerId());assertFalse(result.success());
        assertEquals(5,result.version());assertTrue(result.models().isEmpty());assertFalse(result.toString().contains("secret"));
        assertFalse(result.toString().contains("upstream-private"));
      }
      assertEquals("Bearer rotated-secret",auth.get());assertEquals(5,calls.get());
      status.set(200);body.set("{\"data\":[]}");assertTrue(catalog.list(connection.get().providerId()).success());assertTrue(catalog.list(connection.get().providerId()).models().isEmpty());
      body.set("{\"error\":\"private\"}");assertFalse(catalog.list(connection.get().providerId()).success());
      int before=calls.get();connection.set(new ArkConnectionStore.Connection(6,"ark",connection.get().baseUrl(),""));
      assertFalse(catalog.list(connection.get().providerId()).success());assertEquals(before,calls.get());
    } finally {server.stop(0);}
  }
}
