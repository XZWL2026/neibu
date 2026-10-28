package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/billing/users/{ownerId}/media-prices")
public final class UserMediaPriceAdminController {
  private final MediaPriceStore store;
  private final MediaBilling.Keys keys;
  public UserMediaPriceAdminController(MediaPriceStore store,MediaBilling.Keys keys) { this.store=store;this.keys=keys; }
  @GetMapping public MediaPriceStore.ApiSnapshot get(@PathVariable("ownerId")String ownerId,HttpServletRequest request) {
    return store.loadOwner(ownerId,accountType(ownerId,request));
  }
  @PutMapping public MediaPriceStore.ApiSnapshot save(@PathVariable("ownerId")String ownerId,HttpServletRequest request,@RequestBody SaveRequest body) {
    String type=accountType(ownerId,request);
    return store.saveOwner(ownerId,type,body.requestId(),request.getUserPrincipal().getName(),body.expectedVersion(),body.prices());
  }
  @GetMapping("/audit") public List<MediaPriceStore.ApiAudit> audit(@PathVariable("ownerId")String ownerId,HttpServletRequest request) {
    return store.ownerAudit(ownerId,accountType(ownerId,request));
  }
  private String accountType(String ownerId,HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    if(ownerId==null||!ownerId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid owner");
    var key=keys.forAdministrationOwner(ownerId);
    if(key==null||!ownerId.equals(key.ownerId())||!Set.of("PERSONAL","ENTERPRISE","API").contains(key.accountType())
        ||!("API".equals(key.accountType())?"API":"MEDIA").equals(key.kind()))throw new IllegalArgumentException("business owner required");
    return key.accountType();
  }
  @ExceptionHandler({IllegalArgumentException.class,NullPointerException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) public void invalid() {}
  @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT) public void conflict() {}
  public record SaveRequest(String requestId,long expectedVersion,List<MediaPrice> prices) {}
}
