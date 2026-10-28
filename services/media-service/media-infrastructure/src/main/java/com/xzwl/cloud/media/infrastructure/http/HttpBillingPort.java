package com.xzwl.cloud.media.infrastructure.http;

import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

public final class HttpBillingPort implements BillingPort {
  private final RestClient client;
  public HttpBillingPort(RestClient client){this.client=client;}
  public java.util.List<AvailablePrice> availablePrices(String owner) {
    try {
      var prices=client.get().uri(builder -> builder.path("/internal/v1/billing/media-prices").queryParam("ownerId",owner).build())
          .retrieve().body(AvailablePrice[].class);
      if(prices==null) throw new DependencyUnavailableException("invalid billing prices");
      return java.util.List.of(prices);
    } catch(RestClientResponseException failed){throw failure(failed);}
    catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  public EnhancementCost enhancementQuote(String owner,String target,int seconds) {
    try {
      var quote=client.post().uri("/internal/v1/billing/media-quotes").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ownerId",owner,"type","ENHANCEMENT","model","video-enhancement","resolution",target,"count",1,"durationSeconds",seconds))
          .retrieve().body(EnhancementCost.class);
      if(quote==null || quote.amount()==null || quote.rate()==null || quote.points()==null || quote.amount().signum()<0 || quote.rate().signum()<=0 || quote.points().signum()<0
          || quote.amount().scale()>4 || quote.rate().scale()>4 || quote.points().scale()>4) throw new DependencyUnavailableException("invalid enhancement quote");
      return quote;
    } catch(RestClientResponseException failed){throw failure(failed);}
    catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  public BigDecimal quote(String owner,TaskRequest request) {
    try {
      var quote=client.post().uri("/internal/v1/billing/media-quotes").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ownerId",owner,"type",request.type().name(),"model",request.model(),
              "resolution",request.resolution(),"count",request.count(),"durationSeconds",request.durationSeconds()))
          .retrieve().body(Quote.class);
      if(quote==null || quote.points()==null || quote.points().signum()<0 || quote.points().scale()>4)
        throw new DependencyUnavailableException("invalid billing quote");
      return quote.points().setScale(4);
    } catch(RestClientResponseException failed){throw failure(failed);}
    catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
  public record Quote(BigDecimal points) {}
  public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) {
    try {
      var receipt=client.post().uri("/internal/v1/billing/media-charges").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("operationId",id,"ownerId",owner,"type",request.type().name(),"model",request.model(),
              "resolution",request.resolution(),"count",request.count(),"durationSeconds",request.durationSeconds()))
          .retrieve().body(ChargeReceipt.class);
      if(receipt==null||!id.equals(receipt.chargeId()))throw new DependencyUnavailableException("invalid billing receipt");
      return receipt;
    } catch(RestClientResponseException failed){throw failure(failed);}
    catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  public ChargeReceipt chargeEnhancement(String id,String owner,String target,int seconds,EnhancementCost cost) {
    try {
      var receipt=client.post().uri("/internal/v1/billing/media-charges").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("operationId",id,"ownerId",owner,"type","ENHANCEMENT","model","video-enhancement","resolution",target,"count",1,"durationSeconds",seconds,"expectedCost",cost))
          .retrieve().body(ChargeReceipt.class);
      if(receipt==null || !id.equals(receipt.chargeId()))throw new DependencyUnavailableException("invalid billing receipt");return receipt;
    } catch(RestClientResponseException failed){throw failure(failed);}catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  public void refund(String id,String owner,ChargeReceipt original,BigDecimal points) {
    try {
      client.post().uri("/internal/v1/billing/media-charges/{id}/refund",original.chargeId()).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ownerId",owner,"refundId",id,"points",points)).retrieve().toBodilessEntity();
    } catch(RestClientResponseException failed){throw failure(failed);}
    catch(RestClientException failed){throw new DependencyUnavailableException("billing unavailable");}
  }
  public void settle(Settlement settlement) {
    try { client.post().uri("/internal/v1/billing/media-settlements").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("taskId",settlement.taskId(),"ownerId",settlement.ownerId(),"status",settlement.status(),
            "chargedPoints4",settlement.chargedPoints4(),"refundedPoints4",settlement.refundedPoints4(),
            "occurredAt",settlement.occurredAt().toString())).retrieve().toBodilessEntity(); }
    catch(RestClientException failure) { throw new DependencyUnavailableException("billing settlement unavailable"); }
  }
  private static RuntimeException failure(RestClientResponseException failed) {
    return switch(failed.getStatusCode().value()) {
      case 400,403,409,422 -> new DefinitiveRejectionException("BILLING_REJECTED");
      default -> new DependencyUnavailableException("billing unavailable");
    };
  }
}
