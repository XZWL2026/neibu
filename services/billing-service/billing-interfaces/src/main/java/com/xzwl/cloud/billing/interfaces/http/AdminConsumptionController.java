package com.xzwl.cloud.billing.interfaces.http;
import com.xzwl.cloud.billing.application.ledger.AdminConsumptionQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/billing/consumptions")
public final class AdminConsumptionController {
  private final AdminConsumptionQuery query;
  public AdminConsumptionController(AdminConsumptionQuery query) {this.query=query;}
  @GetMapping public AdminConsumptionQuery.Page list(HttpServletRequest request,
      @RequestParam(name="userId",required=false) String userId,@RequestParam(name="type",required=false) String type,
      @RequestParam(name="model",required=false) String model,@RequestParam(name="status",required=false) String status,
      @RequestParam(name="from",required=false) Instant from,@RequestParam(name="to") Instant to,
      @RequestParam(name="offset",defaultValue="0") int offset,@RequestParam(name="limit",defaultValue="20") int limit) {
    authorize(request);return query.search(new AdminConsumptionQuery.Query(blank(userId),blank(type),blank(model),blank(status),from,to,offset,limit),request.getHeader("Cookie"));
  }
  @GetMapping("/analytics") public AdminConsumptionQuery.Analytics analytics(HttpServletRequest request,
      @RequestParam(name="userId",required=false) String userId,@RequestParam(name="type",required=false) String type,
      @RequestParam(name="model",required=false) String model,@RequestParam(name="status",required=false) String status,
      @RequestParam(name="from",required=false) Instant from,@RequestParam(name="to") Instant to) {
    authorize(request);return query.analytics(new AdminConsumptionQuery.Query(blank(userId),blank(type),blank(model),blank(status),from,to,0,100),request.getHeader("Cookie"));
  }
  @GetMapping("/{id}") public AdminConsumptionQuery.Detail detail(@PathVariable("id") String id,HttpServletRequest request) {
    authorize(request);return query.detail(id,request.getHeader("Cookie"));
  }
  @GetMapping("/{id}/files/{fileId}/access-url") public ResponseEntity<AdminConsumptionQuery.Access> access(
      @PathVariable("id") String id,@PathVariable("fileId") String file,HttpServletRequest request) {
    authorize(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.access(id,file,request.getHeader("Cookie")));
  }
  static void authorize(HttpServletRequest request) {
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!BillingWalletController.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private static String blank(String value) {return value==null||value.isBlank()?null:value.trim();}
  @ExceptionHandler(NoSuchElementException.class) ResponseEntity<Void> missing() {return ResponseEntity.notFound().build();}
  @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class) ResponseEntity<Void> fileMissing() {return ResponseEntity.notFound().build();}
  @ExceptionHandler(org.springframework.web.client.RestClientException.class) ResponseEntity<Void> unavailable() {return ResponseEntity.status(503).build();}
}
