package com.xzwl.cloud.iam.application.account;

import java.io.Serializable;
import java.security.Principal;
import com.xzwl.cloud.iam.domain.account.Administrator;

public record AuthPrincipal(String id, String login, String role, boolean primary, long version,
                            String boundUserId, long boundUserVersion) implements Principal, Serializable {
  public AuthPrincipal(String id, String login, String role, boolean primary, long version) {
    this(id, login, role, primary, version, null, 0);
  }
  public static AuthPrincipal of(Administrator admin) {
    return new AuthPrincipal(admin.id(), admin.username(), admin.role(), admin.primary(), admin.version());
  }
  public boolean user() { return role.equals("USER"); }
  public boolean portal() { return role.equals("API_USER") || role.equals("PROMOTER"); }
  public String ownerId() { return portal() ? boundUserId : id; }
  @Override public String getName() { return (user() ? "user:" : "admin:") + id; }
}
