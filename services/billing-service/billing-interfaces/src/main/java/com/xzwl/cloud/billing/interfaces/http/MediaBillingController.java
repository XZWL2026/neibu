package com.xzwl.cloud.billing.interfaces.http;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/internal/v1/billing")
public final class MediaBillingController {
  private final MediaBilling billing;
  public MediaBillingController(MediaBilling billing){this.billing=billing;}
  @PostMapping("/media-charges") public MediaBilling.Receipt charge(@RequestBody MediaBilling.Charge command){return billing.charge(command);}
  @PostMapping("/media-charges/{operationId}/refund")
  public void refund(@PathVariable("operationId")String operationId,@RequestBody MediaBilling.Refund command){billing.refund(operationId,command);}
  @PostMapping("/media-quotes")
  public MediaBilling.Quote quote(@RequestBody MediaBilling.QuoteRequest request){return billing.quote(request);}
  @GetMapping("/media-prices")
  public java.util.List<MediaBilling.AvailablePrice> prices(@RequestParam("ownerId") String ownerId){return billing.availablePrices(ownerId);}
}
