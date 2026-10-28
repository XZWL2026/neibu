package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.ledger.ConsumptionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class InternalPortalConsumptionController {
  private final ConsumptionStore store;
  private final String token;
  public InternalPortalConsumptionController(ConsumptionStore store, @Value("${abp.internal-token}") String token) {
    this.store=store; this.token=token;
  }
  @PostMapping("/internal/v1/billing/directory-facts")
  public ConsumptionStore.DirectoryFacts directoryFacts(@RequestBody DirectoryRequest body,
      @RequestHeader(name="X-Service-Token", required=false) String supplied) {
    authorize(supplied);return store.directoryFacts(body.owners(),body.from(),body.to());
  }
  @PostMapping("/internal/v1/billing/api-daily-facts")
  public java.util.List<ConsumptionStore.ApiDailyFact> apiDailyFacts(@RequestBody DirectoryRequest body,
      @RequestHeader(name="X-Service-Token",required=false) String supplied) {
    authorize(supplied);return store.apiDailyFacts(body.owners(),body.from(),body.to());
  }
  public record DirectoryRequest(java.util.List<ConsumptionStore.DirectoryOwner> owners,Instant from,Instant to) {}
  @GetMapping("/internal/v1/billing/owners/{ownerId}/directory-summary")
  public OwnerDirectorySummary directorySummary(@PathVariable("ownerId") String ownerId,
      @RequestParam(name="keyId",required=false) String keyId,
      @RequestHeader(name="X-Service-Token",required=false) String supplied) {
    authorize(supplied);var result=store.directoryFinancialSummary(ownerId,keyId,Instant.now());
    return new OwnerDirectorySummary(ownerId,result.summary(),result.netConsumptionAmount4());
  }
  public record OwnerDirectorySummary(String ownerId,ConsumptionStore.Summary summary,String netConsumptionAmount4) {}
  @GetMapping("/internal/v1/billing/owners/{ownerId}/portal-summary")
  public OwnerSummary summary(@PathVariable("ownerId") String ownerId,
      @RequestParam(name="keyId",required=false) String keyId,
      @RequestHeader(name="X-Service-Token", required=false) String supplied) {
    authorize(supplied);
    return new OwnerSummary(ownerId,keyId==null?store.summary(ownerId,Instant.now()):store.directorySummary(ownerId,keyId,Instant.now()));
  }
  @GetMapping("/internal/v1/billing/owners/{ownerId}/allocations/{allocationId}")
  public org.springframework.http.ResponseEntity<ConsumptionStore.AllocationFact> allocation(
      @PathVariable("ownerId") String ownerId, @PathVariable("allocationId") String allocationId,
      @RequestParam("fundingLotId") String fundingLotId,
      @RequestHeader(name="X-Service-Token", required=false) String supplied) {
    authorize(supplied);
    return org.springframework.http.ResponseEntity.of(store.allocation(ownerId,allocationId,fundingLotId));
  }
  private void authorize(String supplied) {
    if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
  public record OwnerSummary(String ownerId, ConsumptionStore.Summary summary) {}
}
