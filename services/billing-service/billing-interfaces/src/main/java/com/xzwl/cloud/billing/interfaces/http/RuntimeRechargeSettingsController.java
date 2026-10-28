package com.xzwl.cloud.billing.interfaces.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.billing.api.RuntimeRechargeSettingsDTO;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.application.recharge.SetRechargeRateHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/settings/runtime")
public final class RuntimeRechargeSettingsController {
  private final RechargeRateStore store;
  private final SetRechargeRateHandler handler;

  public RuntimeRechargeSettingsController(RechargeRateStore store, SetRechargeRateHandler handler) {
    this.store = store;
    this.handler = handler;
  }

  @GetMapping
  public RuntimeRechargeSettingsDTO get(HttpServletRequest request) {
    requireAdministrator(request);
    return response(store.load());
  }

  @PutMapping
  public RuntimeRechargeSettingsDTO put(HttpServletRequest request, @RequestBody JsonNode body) {
    requireAdministrator(request);
    JsonNode value = body.path("rechargePointsPerYuan4");
    JsonNode version = body.path("rechargeRateVersion");
    if (!value.isTextual() || !version.isIntegralNumber() || !version.canConvertToLong()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    try {
      BigDecimal parsed = new BigDecimal(value.textValue());
      // Bound raw input before Decimal4 normalizes its scale (including extreme exponents).
      if (parsed.compareTo(new BigDecimal("0.0001")) < 0
          || parsed.compareTo(new BigDecimal("1e34")) >= 0) {
        throw new IllegalArgumentException("recharge rate is out of range");
      }
      // Only the recharge configuration is written; unrelated settings are never replaced.
      return response(handler.handle(Decimal4.of(parsed), version.longValue()));
    } catch (IllegalArgumentException invalidInput) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    } catch (IllegalStateException staleVersion) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
  }

  private static void requireAdministrator(HttpServletRequest request) {
    // Trust only the authenticated servlet identity, never a client-supplied role/actor header.
    // BrowserSessionFilter supplies only the identity and roles verified by IAM.
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private static RuntimeRechargeSettingsDTO response(RechargeRate rate) {
    return new RuntimeRechargeSettingsDTO(rate.pointsPerYuan4().toString(), rate.version());
  }
}
