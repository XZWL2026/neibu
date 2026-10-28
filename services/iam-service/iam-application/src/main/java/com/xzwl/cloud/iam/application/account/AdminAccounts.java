package com.xzwl.cloud.iam.application.account;

import com.xzwl.cloud.iam.domain.account.Administrator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class AdminAccounts {
  private final AdminStore store;
  private final PasswordHasher passwords;
  private final String dummyHash;
  private final UserDirectory users;
  private final PortalDirectory portals;
  public AdminAccounts(AdminStore store, PasswordHasher passwords) {
    this(store, passwords, null, (role, id) -> false);
  }
  public AdminAccounts(AdminStore store, PasswordHasher passwords, UserDirectory users, PortalDirectory portals) {
    this.users = users;
    this.portals = portals;
    this.store = store;
    this.passwords = passwords;
    dummyHash = passwords.hash(UUID.randomUUID().toString());
  }

  public void bootstrap(Function<String, String> environment, boolean required) {
    store.locked(() -> {
      if (store.count() != 0) return null;
      String username = environment.apply("IAM_BOOTSTRAP_ADMIN_USERNAME");
      String password = environment.apply("IAM_BOOTSTRAP_ADMIN_PASSWORD");
      if (!validUsername(username) || !validPassword(password)) {
        if (required) throw new IllegalStateException("Administrator table is empty: valid IAM_BOOTSTRAP_ADMIN_USERNAME and IAM_BOOTSTRAP_ADMIN_PASSWORD (at least 8 characters, at most 72 UTF-8 bytes) are required");
        return null;
      }
      store.insert(new Administrator(UUID.randomUUID().toString(), username, passwords.hash(password), "ADMIN", true, true, 0));
      return null;
    });
  }

  public AuthPrincipal login(String username, String password) {
    Administrator admin = validUsername(username) ? store.byUsername(username) : null;
    boolean matches = validPassword(password) && passwords.matches(password, admin == null ? dummyHash : admin.passwordHash());
    if (!matches || admin == null || !admin.enabled()) throw new AuthFailure(401, "INVALID_CREDENTIALS");
    if (portalRole(admin.role())) {
      var user = boundUser(admin);
      if (user == null) throw new AuthFailure(401, "INVALID_CREDENTIALS");
      return new AuthPrincipal(admin.id(), admin.username(), admin.role(), false, admin.version(), user.id(), user.authVersion());
    }
    return AuthPrincipal.of(admin);
  }

  public AuthPrincipal portalFor(AuthPrincipal actor, String role) {
    if (actor == null || !actor.user() || !portalRole(role)) throw new AuthFailure(403, "PORTAL_ACCESS_DENIED");
    if ("API_USER".equals(role)) {
      var user = apiUser(actor.id());
      if (user == null || user.authVersion() != actor.version()) throw new AuthFailure(403, "PORTAL_ACCESS_DENIED");
      return new AuthPrincipal(user.id(), user.phone(), role, false, user.authVersion(), user.id(), user.authVersion());
    }
    var account = store.list().stream().filter(a -> role.equals(a.role()) && actor.id().equals(a.boundUserId())).findFirst().orElse(null);
    var user = account == null || !account.enabled() ? null : boundUser(account);
    if (user == null || user.authVersion() != actor.version()) throw new AuthFailure(403, "PORTAL_ACCESS_DENIED");
    return new AuthPrincipal(account.id(), account.username(), role, false, account.version(), user.id(), user.authVersion());
  }

  public AuthPrincipal enterAgent(AuthPrincipal actor, String credentialId) {
    return store.locked(() -> {
      requirePrimary(requireActor(actor));
      Administrator target=store.find(credentialId);
      if (target==null) throw new AuthFailure(404,"ADMIN_NOT_FOUND");
      if (!target.enabled() || !portalRole(target.role())) throw new AuthFailure(403,"PORTAL_ACCESS_DENIED");
      var user=boundUser(target);
      if (user==null) throw new AuthFailure(403,"PORTAL_ACCESS_DENIED");
      store.recordAgentEntry(actor.id(),target.id(),user.id());
      return new AuthPrincipal(target.id(),target.username(),target.role(),false,target.version(),user.id(),user.authVersion());
    });
  }

  public AuthPrincipal enterApiUser(AuthPrincipal actor, String userId) {
    return store.locked(() -> {
      requirePrimary(requireActor(actor));
      var user = users == null ? null : users.find(userId);
      if (user == null || !userId.equals(user.id()) || !user.enabled() || !portals.eligible("API_USER", userId))
        throw new AuthFailure(403, "PORTAL_ACCESS_DENIED");
      store.recordAgentEntry(actor.id(), user.id(), user.id());
      return new AuthPrincipal(user.id(), user.phone(), "API_USER", false, user.authVersion(), user.id(), user.authVersion());
    });
  }

  public AuthPrincipal localPrimary() {
    Administrator admin = store.primary();
    if (admin == null || !admin.enabled()) throw new AuthFailure(401, "INVALID_CREDENTIALS");
    return AuthPrincipal.of(admin);
  }

  public boolean current(AuthPrincipal principal) {
    if ("API_USER".equals(principal.role()) && principal.id().equals(principal.boundUserId())) {
      if (principal.primary() || principal.version() != principal.boundUserVersion()) return false;
      var user = apiUser(principal.id());
      return user != null && user.authVersion() == principal.version();
    }
    Administrator a = store.find(principal.id());
    if (a == null || !a.enabled() || a.version() != principal.version()
        || !a.role().equals(principal.role()) || a.primary() != principal.primary()) return false;
    if (!principal.portal()) return a.boundUserId() == null;
    var user = boundUser(a);
    return user != null && user.id().equals(principal.boundUserId()) && user.authVersion() == principal.boundUserVersion();
  }

  public List<Administrator> list(AuthPrincipal actor) {
    requireActor(actor);
    return store.list().stream().filter(a -> !portalRole(a.role()) || (actor.primary() && actor.role().equals("ADMIN"))).toList();
  }

  public Administrator create(AuthPrincipal actor, String username, String password, String role) {
    return create(actor, username, password, role, null);
  }
  public Administrator create(AuthPrincipal actor, String username, String password, String role, String boundUserId) {
    return store.locked(() -> {
      requirePrimary(requireActor(actor));
      if (!validUsername(username) || !validPassword(password) || !validRole(role)) throw new AuthFailure(400, "INVALID_ADMIN_INPUT");
      if (store.byUsername(username) != null) throw new AuthFailure(409, "USERNAME_EXISTS");
      if (portalRole(role)) {
        if (boundUserId == null || !boundUserId.matches("[A-Za-z0-9._:-]{1,128}")) throw new AuthFailure(400, "INVALID_PORTAL_BINDING");
        var candidate = users == null ? null : users.find(boundUserId);
        if (candidate == null || !boundUserId.equals(candidate.id()) || !candidate.enabled() || !portals.eligible(role, boundUserId))
          throw new AuthFailure(400, "INVALID_PORTAL_BINDING");
        if (store.list().stream().anyMatch(a -> role.equals(a.role()) && boundUserId.equals(a.boundUserId())))
          throw new AuthFailure(409, "PORTAL_ACCOUNT_EXISTS");
      } else if (boundUserId != null) throw new AuthFailure(400, "INVALID_PORTAL_BINDING");
      var admin = new Administrator(UUID.randomUUID().toString(), username, passwords.hash(password), role, false, true, 0, boundUserId);
      store.insert(admin);
      return admin;
    });
  }

  public void changePassword(AuthPrincipal actor, String currentPassword, String newPassword) {
    store.locked(() -> {
      Administrator admin = requireCredential(actor);
      if (!validPassword(currentPassword) || !passwords.matches(currentPassword, admin.passwordHash())) throw new AuthFailure(400, "INVALID_CREDENTIALS");
      if (!validPassword(newPassword)) throw new AuthFailure(400, "INVALID_PASSWORD");
      store.update(new Administrator(admin.id(), admin.username(), passwords.hash(newPassword), admin.role(), admin.primary(), admin.enabled(), admin.version() + 1, admin.boundUserId()));
      return null;
    });
  }

  public void resetPassword(AuthPrincipal actor, String targetId, String password) {
    store.locked(() -> {
      Administrator target = managedTarget(requireActor(actor), targetId);
      if (!validPassword(password)) throw new AuthFailure(400, "INVALID_PASSWORD");
      store.update(new Administrator(target.id(), target.username(), passwords.hash(password), target.role(), target.primary(), target.enabled(), target.version() + 1, target.boundUserId()));
      return null;
    });
  }

  public void setEnabled(AuthPrincipal actor, String targetId, boolean enabled) {
    store.locked(() -> {
      Administrator target = managedTarget(requireActor(actor), targetId);
      if (target.enabled() != enabled) store.update(new Administrator(target.id(), target.username(), target.passwordHash(), target.role(), target.primary(), enabled, target.version() + 1, target.boundUserId()));
      return null;
    });
  }

  public void setRole(AuthPrincipal actor, String targetId, String role) {
    store.locked(() -> {
      Administrator admin = requireActor(actor);
      requirePrimary(admin);
      Administrator target = managedTarget(admin, targetId);
      if (!validRole(role)) throw new AuthFailure(400, "INVALID_ROLE");
      if (portalRole(role) || portalRole(target.role())) throw new AuthFailure(403, "PORTAL_ROLE_IMMUTABLE");
      if (!target.role().equals(role)) store.update(new Administrator(target.id(), target.username(), target.passwordHash(), role, false, target.enabled(), target.version() + 1, target.boundUserId()));
      return null;
    });
  }

  private Administrator requireActor(AuthPrincipal actor) {
    if (actor == null || !(actor.role().equals("ADMIN") || actor.role().equals("NORMAL_ADMIN"))) throw new AuthFailure(403, "ADMIN_REQUIRED");
    return requireCredential(actor);
  }
  private Administrator requireCredential(AuthPrincipal actor) {
    if (actor == null || actor.user() || !current(actor)) throw new AuthFailure(403, "VALID_CREDENTIAL_REQUIRED");
    Administrator credential = store.find(actor.id());
    if (credential == null) throw new AuthFailure(403, "VALID_CREDENTIAL_REQUIRED");
    return credential;
  }
  private static void requirePrimary(Administrator actor) {
    if (!actor.primary() || !actor.role().equals("ADMIN")) throw new AuthFailure(403, "PRIMARY_ADMIN_REQUIRED");
  }
  private Administrator managedTarget(Administrator actor, String targetId) {
    Administrator target = store.find(targetId);
    if (target == null) throw new AuthFailure(404, "ADMIN_NOT_FOUND");
    if (portalRole(target.role())) requirePrimary(actor);
    if (actor.id().equals(targetId) || target.primary()
        || (actor.role().equals("NORMAL_ADMIN") && !target.role().equals("NORMAL_ADMIN"))) throw new AuthFailure(403, "ADMIN_OPERATION_FORBIDDEN");
    return target;
  }
  private UserDirectory.UserIdentity boundUser(Administrator account) {
    if (users == null || account.boundUserId() == null) return null;
    var user = users.find(account.boundUserId());
    return user != null && account.boundUserId().equals(user.id()) && user.enabled() && portals.eligible(account.role(), user.id()) ? user : null;
  }
  private UserDirectory.UserIdentity apiUser(String owner) {
    var user = users == null ? null : users.find(owner);
    return user != null && owner.equals(user.id()) && user.enabled() && portals.apiEntryEligible(owner) ? user : null;
  }
  private static boolean portalRole(String role) { return "API_USER".equals(role) || "PROMOTER".equals(role); }
  private static boolean validRole(String role) { return "ADMIN".equals(role) || "NORMAL_ADMIN".equals(role) || portalRole(role); }
  private static boolean validUsername(String name) { return name != null && name.matches("[A-Za-z0-9一-鿿._@-]{1,64}"); }
  private static boolean validPassword(String value) {
    return value != null && value.codePointCount(0, value.length()) >= 8 && value.getBytes(StandardCharsets.UTF_8).length <= 72;
  }
}
