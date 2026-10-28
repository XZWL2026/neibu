package com.xzwl.cloud.operations.interfaces.http;

import com.xzwl.cloud.operations.application.readmodel.OperationsQueryService;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore.*;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administrator query and internal projection API for all six operations read-model categories. */
@RestController
public final class OperationsController {
  private final OperationsQueryService service;

  public OperationsController(OperationsQueryService service) { this.service = service; }

  @PutMapping("/api/v1/internal/operations/{resource}/{recordId}")
  public OperationalRecord project(HttpServletRequest request, @PathVariable("resource") String resource,
      @PathVariable("recordId") String recordId, @RequestBody ProjectionBody body) {
    requireRole(request, "SERVICE");
    return service.project(new Projection(body.eventId(), type(resource), recordId, body.userId(),
        body.status(), body.amount(), body.occurredAt(), body.summary(), body.detailsJson(),
        body.sourceVersion()));
  }

  @GetMapping("/api/v1/admin/operations/{resource}")
  public Page list(HttpServletRequest request, @PathVariable("resource") String resource,
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "minimumAmount", required = false) BigDecimal minimumAmount,
      @RequestParam(name = "maximumAmount", required = false) BigDecimal maximumAmount,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireRole(request, "ADMIN");
    return service.search(new SearchFilter(type(resource), userId, status, from, to,
        minimumAmount, maximumAmount, page, size));
  }

  @GetMapping("/api/v1/admin/operations/{resource}/{recordId}")
  public OperationalRecord detail(HttpServletRequest request, @PathVariable("resource") String resource,
      @PathVariable("recordId") String recordId) {
    requireRole(request, "ADMIN"); return service.get(type(resource), recordId);
  }

  private static RecordType type(String resource) {
    return switch (resource.toLowerCase(Locale.ROOT)) {
      case "users" -> RecordType.USER;
      case "recharges" -> RecordType.RECHARGE;
      case "consumptions" -> RecordType.CONSUMPTION;
      case "images" -> RecordType.IMAGE;
      case "videos" -> RecordType.VIDEO;
      case "promotions" -> RecordType.PROMOTION;
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    };
  }

  private static void requireRole(HttpServletRequest request, String role) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  public record ProjectionBody(
      String eventId, String userId, String status, BigDecimal amount, Instant occurredAt,
      String summary, String detailsJson, long sourceVersion) {}
}
