package com.xzwl.cloud.billing.infrastructure;
import com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore;
import java.time.Instant;
import org.springframework.web.client.RestClient;
/** An uncertain response leaves the exact payload pending for receiver-side deduplication. */
public final class HttpPromotionSettlementDelivery {
  private final BenefitSettlementStore store;private final RestClient client;
  public HttpPromotionSettlementDelivery(BenefitSettlementStore store,RestClient client){this.store=store;this.client=client;}
  public void tick() {
    Instant now=Instant.now();
    for(var event:store.pending(now)) {
      try {
        var body=new java.util.LinkedHashMap<String,String>();
        body.put("eventId",event.eventId());body.put("kind",event.kind());body.put("settlementId",event.settlementId());
        body.put("allocationId",event.allocationId());body.put("fundingLotId",event.fundingLotId());body.put("orderId",event.orderId());
        body.put("walletId",event.walletId());body.put("walletOwnerUserId",event.walletOwnerUserId());
        body.put("originalPaidAmount4",event.originalPaidAmount4());body.put("originalPoints4",event.originalPoints4());
        body.put("successfulPoints4",event.successfulPoints4());body.put("reversedPoints4",event.reversedPoints4());
        body.put("occurredAt",event.occurredAt().toString());
        client.post().uri("/internal/v1/promotions/consumption-settlements").body(body).retrieve().toBodilessEntity();store.acknowledged(event.eventId());}
      catch(RuntimeException failure){store.retry(event.eventId(),now.plusSeconds(10));}
    }
  }
}
