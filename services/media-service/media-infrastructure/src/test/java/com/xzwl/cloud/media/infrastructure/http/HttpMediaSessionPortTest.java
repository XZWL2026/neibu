package com.xzwl.cloud.media.infrastructure.http;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.xzwl.cloud.media.application.task.MediaSessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpMediaSessionPortTest {
  final RestClient.Builder builder=RestClient.builder().baseUrl("https://iam.example");
  final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
  final HttpMediaSessionPort port=new HttpMediaSessionPort(builder.build());
  @Test void verifiesCookieAndCsrfThroughExistingIamInterface() {
    server.expect(requestTo("https://iam.example/internal/v1/iam/session/verify"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("Cookie","XZWL_SESSION=session"))
        .andExpect(header("X-CSRF-TOKEN","csrf"))
        .andRespond(withSuccess("{\"authenticated\":true,\"user\":{\"id\":\"user-1\"}}",MediaType.APPLICATION_JSON));
    assertEquals("user-1",port.verify("XZWL_SESSION=session","csrf",true)); server.verify();
  }
  @Test void missingCsrfNeverCallsIam() {
    assertEquals(403,assertThrows(MediaSessionPort.Denied.class,() -> port.verify("XZWL_SESSION=session",null,true)).status());
  }
  @Test void revokedSessionIsRejectedOnRead() {
    server.expect(requestTo("https://iam.example/api/v1/auth/session")).andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"authenticated\":false}",MediaType.APPLICATION_JSON));
    assertEquals(401,assertThrows(MediaSessionPort.Denied.class,() -> port.verify("XZWL_SESSION=session",null,false)).status()); server.verify();
  }
}
