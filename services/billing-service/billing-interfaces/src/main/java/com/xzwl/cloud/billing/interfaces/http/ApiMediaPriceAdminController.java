package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/billing/api-users/{ownerId}/media-prices")
public final class ApiMediaPriceAdminController {
  private final MediaPriceStore store;
  private final MediaBilling.Keys keys;
  public ApiMediaPriceAdminController(MediaPriceStore store,MediaBilling.Keys keys){this.store=store;this.keys=keys;}
  @GetMapping public MediaPriceStore.ApiSnapshot get(@PathVariable("ownerId")String ownerId,HttpServletRequest request) {
    authorize(ownerId,request);return store.loadApi(ownerId);
  }
  @PutMapping public MediaPriceStore.ApiSnapshot save(@PathVariable("ownerId")String ownerId,HttpServletRequest request,@RequestBody SaveRequest body) {
    PrimaryAdministrator.require(request);authorize(ownerId,request);
    return store.saveApi(ownerId,body.requestId(),request.getUserPrincipal().getName(),body.expectedVersion(),body.prices());
  }
  @GetMapping("/audit") public List<MediaPriceStore.ApiAudit> audit(@PathVariable("ownerId")String ownerId,HttpServletRequest request) {
    authorize(ownerId,request);return store.apiAudit(ownerId);
  }
  private void authorize(String ownerId,HttpServletRequest request) {
    AdminConsumptionController.authorize(request);
    if(ownerId==null||!ownerId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid owner");
    var key=keys.forAdministrationOwner(ownerId);
    if(key==null||!ownerId.equals(key.ownerId())||!"API".equals(key.accountType())||!"API".equals(key.kind()))throw new IllegalArgumentException("API owner required");
  }
  @ExceptionHandler({IllegalArgumentException.class,NullPointerException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) public void invalid() {}
  @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT) public void conflict() {}
  public record SaveRequest(String requestId,long expectedVersion,List<MediaPrice> prices) {}
}
