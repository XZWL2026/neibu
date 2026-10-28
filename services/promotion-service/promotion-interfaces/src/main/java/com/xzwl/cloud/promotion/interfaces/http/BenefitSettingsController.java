package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class BenefitSettingsController {
  private final PromotionService service;
  public BenefitSettingsController(PromotionService service) { this.service=service; }
  @GetMapping("/api/v1/admin/promotions/benefit-settings")
  public BenefitSettings benefitSettings(HttpServletRequest request) {
    requirePrimary(request);return service.benefitSettings();
  }
  @PostMapping("/api/v1/admin/promotions/benefit-settings")
  public BenefitSettings changeBenefitSettings(HttpServletRequest request,@RequestBody BenefitRateBody body) {
    requirePrimary(request);
    return service.changeBenefitRate(new ChangeBenefitRate(body.commandId(),body.expectedVersion(),body.promoterId(),
        body.field(),body.rate(),body.reason(),request.getUserPrincipal().getName()));
  }
  @GetMapping("/api/v1/admin/promotions/benefit-settings/history")
  public List<BenefitHistory> benefitSettingsHistory(HttpServletRequest request) {
    requirePrimary(request);return service.benefitSettingsHistory();
  }
  private static void requirePrimary(HttpServletRequest request) {
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!request.isUserInRole("ADMIN") || !request.isUserInRole("PRIMARY_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  public record BenefitRateBody(String commandId,long expectedVersion,String promoterId,String field,String rate,String reason) {}
}
