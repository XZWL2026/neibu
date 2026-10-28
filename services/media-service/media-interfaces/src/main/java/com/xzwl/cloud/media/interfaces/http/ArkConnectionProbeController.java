package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.provider.ArkConnectionProbe;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ArkConnectionProbeController {
  private final ArkConnectionProbe probe;
  public ArkConnectionProbeController(ArkConnectionProbe probe) { this.probe=probe; }
  @PostMapping("/api/v1/admin/media/ark-connection/test")
  public ArkConnectionProbe.Result test(@RequestParam("providerId") String providerId, HttpServletRequest request,HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    PrimaryAdministrator.require(request);
    return probe.test(providerId);
  }
}
