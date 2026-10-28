package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.media.application.provider.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MpsConfigurationProbeTest {
  @Test void queriesSavedTemplateMetadataWithoutSubmittingAndRejectsVersionMismatch() throws Exception {
    var actions=new ArrayList<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      actions.add(exchange.getRequestHeaders().getFirst("X-TC-Action"));
      byte[] body="{\"Response\":{\"TranscodeTemplateSet\":[{\"Definition\":12,\"VideoTemplate\":{\"Width\":1920,\"Height\":1080,\"ResolutionAdaptive\":\"open\"}}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      var configuration=new MpsConfigurationStore.Configuration(2,"id","secret","ap-beijing","bucket-123","ap-guangzhou","/enhance/",11,12,13);
      var store=new MpsConfigurationStore() {public Configuration load(){return configuration;}public Configuration save(Configuration requested){throw new AssertionError("probe wrote configuration");}};
      var json=new ObjectMapper();
      var settings=ProviderSettings.from(Map.of("MEDIA_MPS_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort()),json);
      var probe=new TencentMpsConfigurationProbe(store,settings,new TencentMpsAdapter(new ProviderHttp(HttpClient.newHttpClient(),json,Duration.ofSeconds(2)),Clock.systemUTC()),Clock.systemUTC());
      assertEquals(3,probe.templates(configuration).size());
      assertThrows(MpsConfigurationStore.VersionConflict.class,()->probe.test(1,"mps-1080p","16:9"));assertTrue(actions.isEmpty());
      var result=probe.test(2,"mps-1080p","9:16");
      assertTrue(result.success());assertEquals(1080,result.width());assertEquals(1920,result.height());assertEquals(List.of(12L),result.definitions());
      assertEquals(List.of("DescribeTranscodeTemplates"),actions);assertFalse(result.toString().contains("secret"));
    } finally {server.stop(0);}
  }
}
