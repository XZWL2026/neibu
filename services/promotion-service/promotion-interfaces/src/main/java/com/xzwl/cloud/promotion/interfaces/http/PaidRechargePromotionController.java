package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore.PaidRecharge;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal durable Payment delivery; never a browser command. */
@RestController
public final class PaidRechargePromotionController {
  private final PromotionService service;
  private final String token;
  public PaidRechargePromotionController(PromotionService service,
      @Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String token) {
    this.service=service;this.token=token;
  }
  @PostMapping("/internal/v1/promotions/invitation-sources")
  public java.util.List<String> invitationSources(HttpServletRequest request,@RequestBody java.util.List<String> userIds) {
    String supplied=request.getHeader("X-Service-Token");
    if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return service.invitationSources(userIds);
  }
  @PostMapping("/internal/v1/promotions/paid-recharges")
  public Map<String,String> project(HttpServletRequest request, @RequestBody PaidRecharge body) {
    String supplied=request.getHeader("X-Service-Token");
    if(token.isBlank() || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return Map.of("orderId",body.orderId(),"resolution",service.projectPaidRecharge(body));
  }
}
