package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.provider.ArkModelCatalog;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ArkModelCatalogController {
  private final ArkModelCatalog catalog;
  public ArkModelCatalogController(ArkModelCatalog catalog) { this.catalog=catalog; }
  @GetMapping("/api/v1/admin/media/ark-connection/models")
  public ArkModelCatalog.Result models(@RequestParam("providerId") String providerId, HttpServletRequest request,HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    PrimaryAdministrator.require(request);
    return catalog.list(providerId);
  }
}
