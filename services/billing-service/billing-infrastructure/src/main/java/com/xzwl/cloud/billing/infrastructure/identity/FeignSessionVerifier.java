package com.xzwl.cloud.billing.infrastructure.identity;

import com.xzwl.cloud.billing.application.identity.SessionVerifier;
import com.xzwl.cloud.iam.api.SessionDTO;
import feign.FeignException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class FeignSessionVerifier implements SessionVerifier {
  private final IamSessionClient client;
  public FeignSessionVerifier(IamSessionClient client) { this.client = client; }

  public Identity verify(String cookie, String csrfToken, boolean unsafeRequest) {
    if (unsafeRequest && (csrfToken == null || csrfToken.isBlank())) throw new CsrfDenied();
    SessionDTO session;
    try {
      session = unsafeRequest ? client.verify(cookie, csrfToken) : client.read(cookie);
    } catch (FeignException failure) {
      if (failure.status() == 401) throw new Unauthenticated();
      if (failure.status() == 403) throw new CsrfDenied();
      throw new Unavailable();
    }
    if (session == null || !session.authenticated() || session.user() == null
        || session.user().id() == null || session.user().id().isBlank() || session.roles() == null) {
      throw new Unauthenticated();
    }
    Set<String> roles = session.roles().stream().filter(role -> "admin".equals(role) || "normal_admin".equals(role) || "user".equals(role) || "api_user".equals(role))
        .map(role -> role.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    if (roles.isEmpty()) throw new Unauthenticated();
    if(session.primary() && roles.contains("ADMIN")) roles.add("PRIMARY_ADMIN");
    return new Identity(session.user().id(), roles);
  }
}
