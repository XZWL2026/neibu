package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class MediaPriceCatalogController {
  private final MediaPriceStore prices;
  private final RechargeRateStore rates;

  public MediaPriceCatalogController(MediaPriceStore prices, RechargeRateStore rates) {
    this.prices = prices;
    this.rates = rates;
  }

  @GetMapping("/api/v1/billing/model-prices")
  public Catalog get(HttpServletRequest request) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole("USER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var rate = rates.load().pointsPerYuan4();
    var rows = prices.load().prices().stream()
        .filter(p -> p.enabled() && (p.accountType().equals("PERSONAL") || p.accountType().equals("ENTERPRISE")))
        .map(p -> new Price(p.type(), p.accountType(), p.model(), p.resolution(), p.unit(),
            p.yuanPerUnit() != null ? p.yuanPerUnit()
                : new BigDecimal(p.pointsPerUnit()).divide(rate.asBigDecimal(), 4, RoundingMode.DOWN).toPlainString()))
        .toList();
    return new Catalog(rate.toString(), rows);
  }

  public record Catalog(String pointsPerYuan4, List<Price> prices) {}
  public record Price(String type, String accountType, String model, String resolution, String unit, String yuanPerUnit) {}
}
