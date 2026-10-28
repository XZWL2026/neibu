package com.xzwl.cloud.media.infrastructure.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpBillingPortTest {
  @Test void settlementRetainsExactStringAmountsOnDependencyRetry() {
    var builder=RestClient.builder().baseUrl("https://billing.example");
    var server=MockRestServiceServer.bindTo(builder).build();var port=new HttpBillingPort(builder.build());
    var event=new BillingPort.Settlement("task","owner","PARTIAL_SUCCESS","100.0000","25.0000",java.time.Instant.parse("2026-09-15T00:00:00Z"));
    server.expect(requestTo("https://billing.example/internal/v1/billing/media-settlements"))
        .andExpect(content().json("{\"taskId\":\"task\",\"chargedPoints4\":\"100.0000\",\"refundedPoints4\":\"25.0000\",\"occurredAt\":\"2026-09-15T00:00:00Z\"}"))
        .andRespond(withStatus(HttpStatus.CONFLICT));
    assertThrows(DependencyUnavailableException.class,()->port.settle(event));server.verify();
  }
  @Test void ambiguousResponsesStayRetryableAndExplicitRejectionsAreTerminal() {
    var builder=RestClient.builder().baseUrl("https://billing.example");
    var server=MockRestServiceServer.bindTo(builder).build();
    var port=new HttpBillingPort(builder.build());
    var request=new TaskRequest(MediaType.IMAGE,"model","cloud","standard",1,0,List.of());
    server.expect(requestTo("https://billing.example/internal/v1/billing/media-charges")).andRespond(withServerError());
    assertThrows(DependencyUnavailableException.class,()->port.charge("task","owner",request,null));
    server.verify();server.reset();
    server.expect(requestTo("https://billing.example/internal/v1/billing/media-charges"))
        .andRespond(withSuccess("{\"chargeId\":\"other\",\"amount\":1,\"rate\":10,\"points\":10}",org.springframework.http.MediaType.APPLICATION_JSON));
    assertThrows(DependencyUnavailableException.class,()->port.charge("task","owner",request,null));
    server.verify();server.reset();
    server.expect(requestTo("https://billing.example/internal/v1/billing/media-charges")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
    assertThrows(DefinitiveRejectionException.class,()->port.charge("task","owner",request,null));
    server.verify();
  }
}
