package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/media/ark-connection")
public final class ArkConnectionController {
  private final ArkConnectionStore store;
  public ArkConnectionController(ArkConnectionStore store) { this.store = store; }

  @GetMapping public View get(@RequestParam("providerId") String providerId, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    PrimaryAdministrator.require(request);
    try { return view(store.load(providerId)); }
    catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    catch (ArkConnectionStore.NotEditable unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
  }

  @PutMapping public View save(HttpServletRequest request, HttpServletResponse response, @RequestBody SaveRequest body) {
    response.setHeader("Cache-Control", "no-store");
    PrimaryAdministrator.require(request);
    try { return view(store.save(body.expectedVersion(), body.providerId(), body.baseUrl(), body.apiKey(),body.timeoutSeconds(),body.retryCount())); }
    catch (ArkConnectionStore.NotEditable unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
    catch (ArkConnectionStore.VersionConflict conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
    catch (IllegalArgumentException | NullPointerException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
  }

  private View view(ArkConnectionStore.Connection connection) {
    boolean configured = connection.apiKey() != null && !connection.apiKey().isBlank();
    return new View(connection.version(), connection.providerId(), connection.baseUrl(), configured,
        configured ? "***" : "", store.editable(), connection.version() > 0 ? "DATABASE" : "ENVIRONMENT", connection.apiKey(),connection.timeoutSeconds(),connection.retryCount());
  }

  public record View(long version, String providerId, String baseUrl, boolean apiKeyConfigured,
      String maskedApiKey, boolean editable, String source, String apiKey,int timeoutSeconds,int retryCount) {
    @Override public String toString() { return "View[version=" + version + ", apiKey=REDACTED]"; }
  }
  public record SaveRequest(long expectedVersion, String providerId, String baseUrl, String apiKey,Integer timeoutSeconds,Integer retryCount) {
    public SaveRequest(long expectedVersion,String providerId,String baseUrl,String apiKey) {
      this(expectedVersion,providerId,baseUrl,apiKey,null,null);
    }
    @Override public String toString() { return "SaveRequest[apiKey=REDACTED]"; }
  }
}
