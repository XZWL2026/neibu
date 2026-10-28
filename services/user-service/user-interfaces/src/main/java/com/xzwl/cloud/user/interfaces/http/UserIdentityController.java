package com.xzwl.cloud.user.interfaces.http;

import com.xzwl.cloud.user.application.identity.UserIdentityStore;
import com.xzwl.cloud.user.domain.identity.UserIdentity;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class UserIdentityController {
  private final UserIdentityStore users;
  private final com.xzwl.cloud.user.application.identity.AvatarVerifier avatars;
  public UserIdentityController(UserIdentityStore users, com.xzwl.cloud.user.application.identity.AvatarVerifier avatars) { this.users = users; this.avatars = avatars; }
  @PatchMapping("/api/v1/users/me")
  public UserIdentity updateSelf(@RequestBody ProfileUpdate body, Authentication authentication,
      jakarta.servlet.http.HttpServletRequest request) {
    self(authentication);
    if (body.nickname() == null && body.avatarFileId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (body.avatarFileId() != null && !body.avatarFileId().isEmpty()) avatars.verify(body.avatarFileId(), request.getHeader("Cookie"));
    return users.updateProfile(authentication.getName(), body.nickname(), body.avatarFileId());
  }
  public record ProfileUpdate(String nickname, String avatarFileId) {}
  @ExceptionHandler(com.xzwl.cloud.user.application.identity.AvatarVerifier.Rejected.class)
  public org.springframework.http.ResponseEntity<Map<String, String>> avatarFailure(com.xzwl.cloud.user.application.identity.AvatarVerifier.Rejected failure) {
    return org.springframework.http.ResponseEntity.status(failure.status()).body(Map.of("code", "AVATAR_UNAVAILABLE"));
  }
  @PostMapping("/internal/v1/users/resolve")
  public UserIdentity resolve(@RequestBody Phone body) {
    if (body.phone() == null || !body.phone().matches("\\+[1-9][0-9]{7,14}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return users.resolve(body.phone());
  }
  @GetMapping("/internal/v1/users/{id}")
  public UserIdentity find(@PathVariable("id") String id) { return required(id); }
  @GetMapping("/internal/v1/users/{id}/promotion-profile")
  public PromotionProfile promotionProfile(@PathVariable("id") String id) { return new PromotionProfile(required(id), users.registeredAt(id)); }
  @PostMapping("/internal/v1/users/promotion-profiles")
  public java.util.List<PromotionProfile> promotionProfiles(@RequestBody java.util.List<String> ids) {
    if(ids==null||ids.isEmpty()||ids.size()>100||new java.util.HashSet<>(ids).size()!=ids.size()
        ||ids.stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9._:-]{1,128}")))throw new IllegalArgumentException("invalid profile batch");
    return ids.stream().map(this::promotionProfile).toList();
  }
  public record PromotionProfile(UserIdentity user, java.time.Instant registeredAt) {}
  @GetMapping("/api/v1/users/me")
  public UserIdentity self(Authentication authentication) {
    var user = required(authentication.getName());
    if (!user.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return user;
  }
  @GetMapping("/api/v1/admin/users")
  public UserIdentityStore.Page list(@RequestParam(name = "offset", defaultValue = "0") int offset,
      @RequestParam(name = "limit", defaultValue = "50") int limit, @RequestParam(name = "search", defaultValue = "") String search, Authentication authentication) {
    var page = users.list(offset, limit, search.strip());
    return new UserIdentityStore.Page(page.items().stream().map(user -> visible(user, authentication)).toList(), page.total(), page.offset(), page.limit());
  }
  @GetMapping("/api/v1/admin/users/{id}")
  public UserIdentity detail(@PathVariable("id") String id, Authentication authentication) { return visible(required(id), authentication); }
  @PostMapping("/api/v1/admin/users")
  public UserIdentity create(@RequestBody Create body, Authentication authentication) {
    return visible(users.create(body.requestId(), authentication.getName(), body.phone(), body.nickname(), body.source() == null ? null : body.source().name(), body.remark()), authentication);
  }
  private UserIdentity visible(UserIdentity user, Authentication authentication) {
    if (authentication.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"))) return user;
    String phone = user.phone();
    return new UserIdentity(user.id(), phone.substring(0, phone.length() - 8) + "****" + phone.substring(phone.length() - 4), user.nickname(), user.enabled(), user.authVersion(), user.avatarFileId());
  }
  @ExceptionHandler(UserIdentityStore.Conflict.class)
  public org.springframework.http.ResponseEntity<Map<String, String>> conflict(UserIdentityStore.Conflict failure) {
    return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", failure.getMessage()));
  }
  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
    return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
  }
  public enum Source {
    NATURAL, REFERRAL, ADMIN;
    @com.fasterxml.jackson.annotation.JsonCreator
    public static Source from(String value) { return Source.valueOf(value); }
  }
  public record Create(String requestId, String phone, String nickname, Source source, String remark) {}
  @PatchMapping("/api/v1/admin/users/{id}/status")
  public Map<String, Boolean> status(@PathVariable("id") String id, @RequestBody Status body) {
    if (body.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    required(id); users.setEnabled(id, body.enabled());
    return Map.of("success", true);
  }
  private UserIdentity required(String id) {
    var user = users.find(id);
    if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return user;
  }
  public record Phone(String phone) {}
  public record Status(Boolean enabled) {}
  @PatchMapping("/api/v1/admin/users/{id}/nickname")
  public UserIdentity rename(@PathVariable("id") String id, @RequestBody NicknameUpdate body, Authentication authentication) {
    required(id);
    var user = users.rename(id, body.nickname());
    if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return visible(user, authentication);
  }
  public record NicknameUpdate(String nickname) {}
}
