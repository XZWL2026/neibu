package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.ledger.ConsumptionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/billing/portal")
public final class PortalConsumptionController {
  private final ConsumptionStore store;
  public PortalConsumptionController(ConsumptionStore store) { this.store = store; }
  @GetMapping("/consumptions")
  public ConsumptionStore.Page consumptions(HttpServletRequest request,
      @RequestParam(name="type", required=false) String type, @RequestParam(name="model", required=false) String model,
      @RequestParam(name="from", required=false) Instant from, @RequestParam(name="to", required=false) Instant to,
      @RequestParam(name="offset", defaultValue="0") int offset, @RequestParam(name="limit", defaultValue="20") int limit) {
    return store.search(new ConsumptionStore.Filter(owner(request), type, model, from, to == null ? Instant.now() : to, offset, limit));
  }
  @GetMapping("/summary")
  public ConsumptionStore.Summary summary(HttpServletRequest request,
      @RequestParam(name="from", required=false) Instant from, @RequestParam(name="to", required=false) Instant to) {
    String owner = owner(request); Instant cutoff = to == null ? Instant.now() : to;
    if (from != null && from.isAfter(cutoff)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return store.summary(owner, from, cutoff);
  }
  private static String owner(HttpServletRequest request) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole("API_USER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return request.getUserPrincipal().getName();
  }
}
