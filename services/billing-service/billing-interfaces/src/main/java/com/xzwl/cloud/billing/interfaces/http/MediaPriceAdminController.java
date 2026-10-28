package com.xzwl.cloud.billing.interfaces.http;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/billing/media-prices")
public final class MediaPriceAdminController {
  private final MediaPriceStore store;
  public MediaPriceAdminController(MediaPriceStore store) { this.store=store; }
  @GetMapping public MediaPriceStore.Snapshot get(HttpServletRequest request) { PrimaryAdministrator.require(request);return store.load(); }
  @PutMapping public MediaPriceStore.Snapshot save(HttpServletRequest request,@RequestBody SaveRequest body) {
    PrimaryAdministrator.require(request);
    try { return store.save(body.expectedVersion(),body.prices()); }
    catch(IllegalArgumentException | NullPointerException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    catch(IllegalStateException conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
  }
  public record SaveRequest(long expectedVersion,List<MediaPrice> prices) {}
}
