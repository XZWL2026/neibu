package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.*;
import com.xzwl.cloud.iam.domain.account.Administrator;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AdminAccountController {
  private final AdminAccounts admins;
  public AdminAccountController(AdminAccounts admins) { this.admins = admins; }
  @GetMapping("/api/v1/admin/admins")
  public List<AdminView> list(@AuthenticationPrincipal AuthPrincipal actor) { return admins.list(actor).stream().map(AdminView::of).toList(); }
  @PostMapping("/api/v1/admin/admins")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public AdminView create(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody Create body) { return AdminView.of(admins.create(actor, body.username(), body.password(), body.role(), body.boundUserId())); }
  @PostMapping("/api/v1/auth/admin/password")
  public Map<String, Boolean> password(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody PasswordChange body) {
    admins.changePassword(actor, body.currentPassword(), body.newPassword()); return Map.of("success", true);
  }
  @PostMapping("/api/v1/admin/admins/{id}/password")
  public Map<String, Boolean> reset(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable("id") String id, @RequestBody PasswordReset body) {
    admins.resetPassword(actor, id, body.password()); return Map.of("success", true);
  }
  @PatchMapping("/api/v1/admin/admins/{id}/status")
  public Map<String, Boolean> status(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable("id") String id, @RequestBody Status body) {
    if (body.enabled() == null) throw new AuthFailure(400, "INVALID_STATUS");
    admins.setEnabled(actor, id, body.enabled()); return Map.of("success", true);
  }
  @PatchMapping("/api/v1/admin/admins/{id}/role")
  public Map<String, Boolean> role(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable("id") String id, @RequestBody Role body) {
    admins.setRole(actor, id, body.role()); return Map.of("success", true);
  }
  public record AdminView(String id, String username, String role, boolean primary, boolean enabled, String boundUserId) {
    static AdminView of(Administrator a) { return new AdminView(a.id(), a.username(), a.role(), a.primary(), a.enabled(), a.boundUserId()); }
  }
  public record Create(String username, String password, String role, String boundUserId) { @Override public String toString() { return "Create[redacted]"; } }
  public record PasswordChange(String currentPassword, String newPassword) { @Override public String toString() { return "PasswordChange[redacted]"; } }
  public record PasswordReset(String password) { @Override public String toString() { return "PasswordReset[redacted]"; } }
  public record Status(Boolean enabled) {}
  public record Role(String role) {}
}
