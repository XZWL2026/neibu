package com.xzwl.cloud.media.interfaces.http;
import com.xzwl.cloud.web.PrimaryAdministrator;
import com.xzwl.cloud.web.ProviderConfigurationStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;
@RestController
public final class ProviderStatusController {
  private final Supplier<ProviderConfigurationStatus> status;
  public ProviderStatusController(Supplier<ProviderConfigurationStatus> status) { this.status=status; }
  @GetMapping("/api/v1/admin/media/provider-status")
  public ProviderConfigurationStatus get(HttpServletRequest request) { PrimaryAdministrator.require(request);return status.get(); }
}
